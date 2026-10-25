package com.gitgo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LockScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val activity = ctx.findFragmentActivity()
    LaunchedEffect(Unit) { activity?.let { vm.unlock(it) } }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Lock, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(tr(R.string.vault_locked), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { activity?.let { vm.unlock(it) } }) { Text(tr(R.string.vault_unlock)) }
        TextButton(onClick = { vm.signOut() }) { Text(tr(R.string.sign_out)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(vm: MainVm) {
    BackHandler { vm.screen = Screen.Settings }
    val ctx = LocalContext.current
    val activity = ctx.findFragmentActivity()
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(vm.prefs.vaultEnabled) }
    var keys by remember { mutableStateOf(Vault.loadKeys(vm.prefs)) }
    var newName by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<SshKeyInfo?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr(R.string.vault_title)) },
            navigationIcon = { IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back)) } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.vault_protect), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(if (enabled) R.string.vault_enabled else R.string.vault_disabled),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Switch(checked = enabled, onCheckedChange = { on ->
                    val act = activity
                    if (act != null) {
                        info = null
                        if (on) {
                            if (!Vault.canUseBiometrics(ctx)) info = tr(R.string.vault_no_bio)
                            else Vault.enableForToken(act, vm.prefs) { err -> enabled = vm.prefs.vaultEnabled; info = err }
                        } else {
                            val t = vm.prefs.sessionToken
                            if (t != null) { vm.prefs.disableVault(t); enabled = false }
                        }
                    }
                })
            }
            Text(tr(R.string.vault_protect_desc), style = MaterialTheme.typography.bodySmall)
            info?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider()
            Text(tr(R.string.ssh_title), style = MaterialTheme.typography.titleMedium)
            Text(tr(R.string.ssh_note), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    newName, { newName = it }, label = { Text(tr(R.string.ssh_name)) },
                    singleLine = true, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = newName.isNotBlank() && activity != null,
                    onClick = {
                        val act = activity ?: return@Button
                        if (!Vault.canUseBiometrics(ctx)) { info = tr(R.string.vault_no_bio); return@Button }
                        Vault.generateKey(act, vm.prefs, newName.trim()) { err ->
                            info = err ?: tr(R.string.ssh_created)
                            keys = Vault.loadKeys(vm.prefs)
                            if (err == null) newName = ""
                        }
                    }
                ) { Text(tr(R.string.ssh_generate)) }
            }
            if (keys.isEmpty()) Text(tr(R.string.ssh_none), style = MaterialTheme.typography.bodySmall)
            keys.forEach { k ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(k.name, fontWeight = FontWeight.Bold)
                        Text(tr(R.string.ssh_fp, k.fp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text(k.pub, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 2)
                        Row {
                            TextButton(onClick = { copyText(ctx, "ssh", k.pub); info = tr(R.string.ci_copied) }) { Text(tr(R.string.ssh_copy_pub)) }
                            TextButton(onClick = {
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { GitHubApi(vm.prefs.token).addSshKey(k.name, k.pub) } }
                                    info = r.exceptionOrNull()?.let { tr(R.string.ssh_add_failed, it.message ?: "") } ?: tr(R.string.ssh_added)
                                }
                            }) { Text(tr(R.string.ssh_add_github)) }
                        }
                        Row {
                            TextButton(onClick = {
                                val act = activity ?: return@TextButton
                                Vault.exportPrivate(act, vm.prefs, k) { pem, err ->
                                    if (pem != null) { copyText(ctx, "ssh", pem, sensitive = true); info = tr(R.string.ssh_export_warn) }
                                    else info = err
                                }
                            }) { Text(tr(R.string.ssh_export)) }
                            TextButton(onClick = { toDelete = k }) { Text(tr(R.string.ssh_delete)) }
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { k ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(tr(R.string.del_title, k.name)) },
            confirmButton = {
                TextButton(onClick = {
                    Vault.saveKeys(vm.prefs, Vault.loadKeys(vm.prefs).filter { it.fp != k.fp })
                    keys = Vault.loadKeys(vm.prefs); toDelete = null
                }) { Text(tr(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(tr(R.string.cancel)) } }
        )
    }
}
