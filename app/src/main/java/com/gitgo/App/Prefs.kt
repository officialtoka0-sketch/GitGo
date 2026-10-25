package com.gitgo.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Шифрование токена ключом AES-GCM из Android Keystore (без биометрии; для биометрии есть Vault). */
private object TokenCrypto {
    private const val ALIAS = "gitgo_token_key"
    private const val KS = "AndroidKeyStore"

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        kg.init(spec)
        return kg.generateKey()
    }

    fun warmUp() { runCatching { key() } }

    fun encrypt(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(text.toByteArray())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** null — ключ сброшен или данные повреждены (нужно войти заново). */
    fun decrypt(blob: String): String? = try {
        val p = blob.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)))
    } catch (e: Exception) {
        null
    }
}

class Prefs(private val appCtx: Context) {
    private val plain: SharedPreferences = appCtx.getSharedPreferences("gitgo", Context.MODE_PRIVATE)

    // Если Keystore недоступен, токен живёт только в памяти и никогда не пишется на диск открытым текстом.
    private var cryptoOk = true
    private var memToken = ""
    @Volatile private var cached: String? = null

    /** Прогрев ключа Keystore в фоне — чтобы первое чтение токена не тормозило главный поток. */
    fun warmUp() { TokenCrypto.warmUp() }

    private fun loadToken(): String {
        cached?.let { return it }
        val value = if (!cryptoOk) memToken else {
            val blob = plain.getString("tokenEnc", "") ?: ""
            if (blob.isEmpty()) memToken else TokenCrypto.decrypt(blob) ?: ""
        }
        cached = value
        return value
    }

    private fun storeToken(v: String?) {
        val value = v ?: ""
        cached = value
        if (value.isEmpty()) {
            plain.edit().remove("tokenEnc").apply()
            memToken = ""
            return
        }
        try {
            plain.edit().putString("tokenEnc", TokenCrypto.encrypt(value)).apply()
            memToken = ""
        } catch (e: Exception) {
            cryptoOk = false
            memToken = value
            plain.edit().remove("tokenEnc").apply()
        }
    }

    /** Токен в памяти на время разблокированной сессии (если включена защита биометрией). */
    @Volatile var sessionToken: String? = null

    var vaultEnabled: Boolean
        get() = plain.getBoolean("vaultEnabled", false)
        set(v) { plain.edit().putBoolean("vaultEnabled", v).apply() }

    /** Токен, зашифрованный ключом Keystore: "iv:ciphertext" (base64). */
    var vaultBlob: String
        get() = plain.getString("vaultBlob", "") ?: ""
        set(v) { plain.edit().putString("vaultBlob", v).apply() }

    var token: String
        get() = if (vaultEnabled) sessionToken ?: "" else loadToken()
        set(v) {
            if (vaultEnabled) sessionToken = v.ifBlank { null }
            else storeToken(v)
        }

    val hasToken: Boolean get() = vaultEnabled || token.isNotBlank()

    fun enableVault(blob: String, token: String) {
        vaultBlob = blob
        storeToken(null)
        vaultEnabled = true
        sessionToken = token
    }

    fun disableVault(token: String) {
        vaultEnabled = false
        vaultBlob = ""
        sessionToken = null
        storeToken(token)
    }

    fun clearAuth() {
        storeToken(null)
        vaultEnabled = false; vaultBlob = ""; sessionToken = null
        login = ""; userId = 0
    }

    var login: String
        get() = plain.getString("login", "") ?: ""
        set(v) { plain.edit().putString("login", v).apply() }

    var userId: Long
        get() = plain.getLong("userId", 0L)
        set(v) { plain.edit().putLong("userId", v).apply() }

    var userName: String
        get() = plain.getString("userName", "") ?: ""
        set(v) { plain.edit().putString("userName", v).apply() }

    var userEmail: String
        get() = plain.getString("userEmail", "") ?: ""
        set(v) { plain.edit().putString("userEmail", v).apply() }

    var themeName: String
        get() = plain.getString("theme", ThemeKind.DARK.name) ?: ThemeKind.DARK.name
        set(v) { plain.edit().putString("theme", v).apply() }

    /** AUTO | API | GIT */
    var sendMode: String
        get() = plain.getString("sendMode", "AUTO") ?: "AUTO"
        set(v) { plain.edit().putString("sendMode", v).apply() }

    var onboarded: Boolean
        get() = plain.getBoolean("onboarded", false)
        set(v) { plain.edit().putBoolean("onboarded", v).apply() }

    /** Закреплённый репозиторий для виджета CI: "owner/name". */
    var pinnedRepo: String
        get() = plain.getString("pinnedRepo", "") ?: ""
        set(v) { plain.edit().putString("pinnedRepo", v).apply() }

    var sshKeysJson: String
        get() = plain.getString("sshKeys", "[]") ?: "[]"
        set(v) { plain.edit().putString("sshKeys", v).apply() }
}
