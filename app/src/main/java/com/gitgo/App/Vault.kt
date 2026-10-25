package com.gitgo.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

fun copyText(ctx: Context, label: String, text: String, sensitive: Boolean = false) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    cm.setPrimaryClip(clip)
}

data class SshKeyInfo(val name: String, val pub: String, val fp: String, val iv: String, val enc: String, val ts: Long)

/**
 * Хранилище секретов: ключ AES-GCM живёт в Android Keystore и требует отпечаток при каждом использовании
 * (BiometricPrompt + CryptoObject). Им шифруются токен GitHub и закрытые SSH-ключи.
 */
object Vault {
    private const val ALIAS = "gitgo_vault_key"
    private const val KS = "AndroidKeyStore"
    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG

    fun canUseBiometrics(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        kg.init(b.build())
        return kg.generateKey()
    }

    fun deleteKey() { runCatching { KeyStore.getInstance(KS).apply { load(null) }.deleteEntry(ALIAS) } }

    private fun encryptCipher(): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }

    private fun decryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun invalidated(prefs: Prefs) {
        deleteKey()
        prefs.sshKeysJson = "[]"
    }

    private fun prompt(
        activity: FragmentActivity, cipher: Cipher,
        onOk: (Cipher) -> Unit, onFail: (String) -> Unit
    ) {
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c != null) {
                    try { onOk(c) } catch (e: Exception) { onFail(e.message ?: e.javaClass.simpleName) }
                } else onFail("no cipher")
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onFail(errString.toString()) }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(tr(R.string.vault_prompt_title))
            .setSubtitle(tr(R.string.vault_prompt_sub))
            .setNegativeButtonText(tr(R.string.cancel))
            .setAllowedAuthenticators(AUTH)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), cb)
            .authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    // ───────── токен ─────────

    fun enableForToken(activity: FragmentActivity, prefs: Prefs, done: (String?) -> Unit) {
        val token = prefs.token
        if (token.isBlank()) { done(tr(R.string.vault_no_bio)); return }
        val cipher = try { encryptCipher() } catch (e: Exception) { done(e.message); return }
        prompt(activity, cipher, { c ->
            val ct = c.doFinal(token.toByteArray())
            prefs.enableVault(b64(c.iv) + ":" + b64(ct), token)
            done(null)
        }, { done(it) })
    }

    fun unlock(activity: FragmentActivity, prefs: Prefs, done: (String?) -> Unit) {
        val parts = prefs.vaultBlob.split(":")
        if (parts.size != 2) { done("vault data"); return }
        val cipher = try {
            decryptCipher(unb64(parts[0]))
        } catch (e: KeyPermanentlyInvalidatedException) {
            invalidated(prefs); done(tr(R.string.vault_invalid)); return
        } catch (e: Exception) { done(e.message); return }
        prompt(activity, cipher, { c ->
            prefs.sessionToken = String(c.doFinal(unb64(parts[1])))
            done(null)
        }, { done(it) })
    }

    // ───────── SSH-ключи ─────────

    fun loadKeys(prefs: Prefs): List<SshKeyInfo> {
        val a = runCatching { JSONArray(prefs.sshKeysJson) }.getOrDefault(JSONArray())
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            SshKeyInfo(o.getString("name"), o.getString("pub"), o.getString("fp"), o.getString("iv"), o.getString("enc"), o.optLong("ts"))
        }
    }

    fun saveKeys(prefs: Prefs, list: List<SshKeyInfo>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("name", it.name).put("pub", it.pub).put("fp", it.fp)
                .put("iv", it.iv).put("enc", it.enc).put("ts", it.ts))
        }
        prefs.sshKeysJson = a.toString()
    }

    fun generateKey(activity: FragmentActivity, prefs: Prefs, name: String, done: (String?) -> Unit) {
        val g = SshKeys.generate(name)
        val cipher = try { encryptCipher() } catch (e: Exception) { done(e.message); return }
        prompt(activity, cipher, { c ->
            val ct = c.doFinal(g.privatePem.toByteArray())
            val info = SshKeyInfo(name, g.publicLine, g.fingerprint, b64(c.iv), b64(ct), System.currentTimeMillis())
            saveKeys(prefs, loadKeys(prefs) + info)
            done(null)
        }, { done(it) })
    }

    /** Расшифровывает закрытый ключ после отпечатка. */
    fun exportPrivate(activity: FragmentActivity, prefs: Prefs, key: SshKeyInfo, done: (String?, String?) -> Unit) {
        val cipher = try {
            decryptCipher(unb64(key.iv))
        } catch (e: KeyPermanentlyInvalidatedException) {
            invalidated(prefs); done(null, tr(R.string.vault_invalid)); return
        } catch (e: Exception) { done(null, e.message); return }
        prompt(activity, cipher, { c -> done(String(c.doFinal(unb64(key.enc))), null) }, { done(null, it) })
    }
}

/** Генерация ключей Ed25519 и запись в формате OpenSSH. */
object SshKeys {
    class Generated(val publicLine: String, val privatePem: String, val fingerprint: String)

    private fun str(o: DataOutputStream, b: ByteArray) { o.writeInt(b.size); o.write(b) }

    fun generate(comment: String): Generated {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        val seed = (kp.private as Ed25519PrivateKeyParameters).encoded
        val pub = (kp.public as Ed25519PublicKeyParameters).encoded
        val type = "ssh-ed25519".toByteArray()

        val blobBytes = ByteArrayOutputStream()
        DataOutputStream(blobBytes).also { str(it, type); str(it, pub) }
        val pubBlob = blobBytes.toByteArray()

        val priv = ByteArrayOutputStream()
        val p = DataOutputStream(priv)
        val check = SecureRandom().nextInt()
        p.writeInt(check); p.writeInt(check)
        str(p, type); str(p, pub); str(p, seed + pub); str(p, comment.toByteArray())
        var pad = 1
        while (priv.size() % 8 != 0) p.writeByte(pad++)

        val out = ByteArrayOutputStream()
        val o = DataOutputStream(out)
        o.write("openssh-key-v1".toByteArray()); o.writeByte(0)
        str(o, "none".toByteArray()); str(o, "none".toByteArray()); str(o, ByteArray(0))
        o.writeInt(1)
        str(o, pubBlob)
        str(o, priv.toByteArray())

        val body = java.util.Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(out.toByteArray())
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
        val pubLine = "ssh-ed25519 " + java.util.Base64.getEncoder().encodeToString(pubBlob) + " " + comment
        val fp = "SHA256:" + java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(pubBlob))
        return Generated(pubLine, pem, fp)
    }
}
