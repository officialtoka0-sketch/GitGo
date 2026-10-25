package com.gitgo.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val PRIVACY_URL = "https://github.com/YOUR_NAME/GitGo/blob/main/PRIVACY_POLICY.md"

// ───────────────────────── Заставка с GIF ─────────────────────────

@Composable
fun SplashScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val loader = remember {
        ImageLoader.Builder(ctx).components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
    LaunchedEffect(Unit) { delay(3000); onDone() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = "file:///android_asset/gitgo_intro.gif",
            imageLoader = loader,
            contentDescription = "GitGo",
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit
        )
    }
}

// ───────────────────────── Знакомство (раскрытие доступа к файлам) ─────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Onboarding(onFinish: () -> Unit) {
    data class Page(val icon: ImageVector, val title: String, val text: String)
    val pages = listOf(
        Page(
            Icons.Filled.FolderOpen, tr(R.string.onb1_title),
            tr(R.string.onb1_text)
        ),
        Page(
            Icons.Filled.Bolt, tr(R.string.onb2_title),
            tr(R.string.onb2_text)
        ),
        Page(
            Icons.Filled.Terminal, tr(R.string.onb3_title),
            tr(R.string.onb3_text)
        )
    )
    val state = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onFinish() }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(state, Modifier.weight(1f)) { i ->
            val page = pages[i]
            val scale by animateFloatAsState(if (state.currentPage == i) 1f else 0.6f, label = "icon")
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(page.icon, null, Modifier.size(120.dp).scale(scale), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Text(page.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(page.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(12.dp)) {
            pages.indices.forEach { i ->
                Box(
                    Modifier.size(if (i == state.currentPage) 12.dp else 8.dp).clip(CircleShape)
                        .background(if (i == state.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
        if (state.currentPage == pages.lastIndex) {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else onFinish()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr(R.string.onb_allow)) }
            TextButton(onClick = onFinish) { Text(tr(R.string.onb_start)) }
        } else {
            Button(
                onClick = { scope.launch { state.animateScrollToPage(state.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr(R.string.onb_next)) }
            TextButton(onClick = onFinish) { Text(tr(R.string.onb_skip)) }
        }
    }
}

// ───────────────────────── Общие элементы ─────────────────────────

@Composable
fun TransferCard(p: Progress) {
    val ctx = LocalContext.current
    val running = p.state == Progress.State.RUNNING
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (running) {
                if (p.percent >= 0) LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val text = if (running && p.message.isNotBlank() && p.total == 0L) p.message else p.line()
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Row {
                if (running) {
                    TextButton(onClick = {
                        ctx.startService(Intent(ctx, TransferService::class.java).setAction(TransferService.ACTION_CANCEL))
                    }) { Text(tr(R.string.cancel)) }
                } else {
                    TextButton(onClick = { TransferState.current.value = null }) { Text(tr(R.string.hide)) }
                }
            }
        }
    }
}

@Composable
fun LoginCard(vm: MainVm) {
    var token by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.login_title), style = MaterialTheme.typography.titleMedium)
            Text(
                tr(R.string.login_text),
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Personal access token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.signIn(token.trim()) }, enabled = token.isNotBlank() && !vm.loading) { Text(tr(R.string.login_btn)) }
                TextButton(onClick = { uri.openUri("https://github.com/settings/tokens/new?scopes=repo&description=GitGo") }) {
                    Text(tr(R.string.create_token))
                }
            }
        }
    }
}

// ───────────────────────── Главный экран ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val progress by TransferState.current.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<java.io.File?>(null) }

    LaunchedEffect(progress?.state) { if (progress?.state != Progress.State.RUNNING) vm.refreshLocal() }
    LaunchedEffect(vm.loggedIn) { if (vm.loggedIn && vm.remoteRepos.isEmpty()) vm.loadRemote() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitGo", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { openTerminal(ctx, null) }) { Icon(Icons.Filled.Terminal, tr(R.string.terminal)) }
                    IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.Filled.Settings, tr(R.string.settings)) }
                }
            )
        },
        floatingActionButton = {
            if (vm.loggedIn) ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(tr(R.string.new_repo)) }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            progress?.let { p -> item { TransferCard(p) } }
            if (!vm.loggedIn) item { LoginCard(vm) }

            item { Text(tr(R.string.on_device), style = MaterialTheme.typography.titleMedium) }
            if (vm.localRepos.isEmpty()) {
                item { Text(tr(R.string.on_device_empty), style = MaterialTheme.typography.bodySmall) }
            }
            items(vm.localRepos, key = { it.absolutePath }) { dir ->
                ElevatedCard(onClick = { vm.screen = Screen.Repo(dir) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Text(dir.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        IconButton(onClick = { toDelete = dir }) { Icon(Icons.Filled.Delete, tr(R.string.delete)) }
                    }
                }
            }

            if (vm.loggedIn) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GitHub @${vm.login}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { vm.loadRemote() }) { Icon(Icons.Filled.Refresh, tr(R.string.refresh)) }
                    }
                }
                if (vm.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                vm.error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
                items(vm.remoteRepos, key = { it.owner + "/" + it.name }) { r ->
                    val have = vm.localRepos.any { it.name == r.name }
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (r.isPrivate) {
                                        Icon(Icons.Filled.Lock, null, Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text("${r.owner}/${r.name}", fontWeight = FontWeight.Medium)
                                }
                                if (r.description.isNotBlank()) Text(r.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                            if (have) Text(tr(R.string.on_device_small), style = MaterialTheme.typography.labelSmall)
                            else IconButton(onClick = { vm.clone(r) }) { Icon(Icons.Filled.CloudDownload, tr(R.string.clone)) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showCreate) CreateRepoDialog(onDismiss = { showCreate = false }) { n, p, d -> showCreate = false; vm.createRepo(n, p, d) }
    toDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(tr(R.string.del_local_title)) },
            text = { Text(tr(R.string.del_local_text, d.name)) },
            confirmButton = { TextButton(onClick = { vm.deleteLocal(d); toDelete = null }) { Text(tr(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(tr(R.string.cancel)) } }
        )
    }
}

@Composable
fun CreateRepoDialog(onDismiss: () -> Unit, onCreate: (String, Boolean, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priv by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.new_repo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr(R.string.name_label)) }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text(tr(R.string.description)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.private_repo), Modifier.weight(1f))
                    Switch(priv, { priv = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim(), priv, desc.trim()) }, enabled = name.isNotBlank()) { Text(tr(R.string.create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } }
    )
}

// ───────────────────────── Настройки ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainVm) {
    BackHandler { vm.screen = Screen.Home }
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    var name by remember { mutableStateOf(vm.prefs.userName) }
    var email by remember { mutableStateOf(vm.prefs.userEmail) }
    var mode by remember { mutableStateOf(vm.prefs.sendMode) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr(R.string.settings)) },
            navigationIcon = { IconButton(onClick = { vm.screen = Screen.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back)) } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(tr(R.string.settings_theme), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ThemeKind.values().forEach { k ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { vm.updateTheme(k) }) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(k.swatch).border(
                                if (vm.theme == k) 3.dp else 1.dp,
                                if (vm.theme == k) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                CircleShape
                            )
                        )
                        Text(tr(k.labelRes), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Text(tr(R.string.settings_mode), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("AUTO" to tr(R.string.mode_auto), "API" to tr(R.string.mode_api), "GIT" to "Git push").forEach { (v, label) ->
                    FilterChip(selected = mode == v, onClick = { mode = v; vm.prefs.sendMode = v }, label = { Text(label) })
                }
            }
            Text(
                tr(R.string.mode_hint),
                style = MaterialTheme.typography.bodySmall
            )

            Text(tr(R.string.settings_author), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, label = { Text(tr(R.string.name_short)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.prefs.userName = name.trim(); vm.prefs.userEmail = email.trim() }) { Text(tr(R.string.save)) }

            Text(tr(R.string.settings_account), style = MaterialTheme.typography.titleMedium)
            if (vm.loggedIn) {
                Text(tr(R.string.signed_as, vm.login))
                Button(onClick = { vm.signOut() }) { Text(tr(R.string.sign_out)) }
            } else Text(tr(R.string.not_signed))

            Button(onClick = { vm.screen = Screen.Vault }) {
                Icon(Icons.Filled.Lock, null)
                Spacer(Modifier.width(8.dp))
                Text(tr(R.string.vault_btn))
            }

            Text(tr(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        ctx.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)))
                    } else {
                        ctx.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS))
                    }
                } catch (e: Exception) { }
            }) { Text(tr(R.string.language_btn)) }

            Text(tr(R.string.settings_notif), style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                )
            }) { Text(tr(R.string.notif_settings)) }
            TextButton(onClick = { uri.openUri(PRIVACY_URL) }) { Text(tr(R.string.privacy)) }

            Text(
                tr(R.string.about),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
