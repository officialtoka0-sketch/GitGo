package com.gitgo.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ScrollableTabRow
import java.util.Locale

sealed interface Overlay {
    data class Diff(val path: String) : Overlay
    data class Edit(val path: String) : Overlay
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(vm: MainVm, dir: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val progress by TransferState.current.collectAsState()
    var branch by remember { mutableStateOf("") }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var menu by remember { mutableStateOf(false) }
    var filesSub by rememberSaveable { mutableStateOf("") }
    var commitMsg by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val tabs = listOf(tr(R.string.tab_files), tr(R.string.tab_changes), tr(R.string.tab_history), tr(R.string.tab_branches), tr(R.string.tab_ci))

    LaunchedEffect(progress?.state) {
        if (progress?.state != null && progress?.state != Progress.State.RUNNING) tick++
    }
    LaunchedEffect(tick) {
        branch = withContext(Dispatchers.IO) {
            try { Git.open(dir).use { it.repository.branch } } catch (e: Exception) { "?" }
        }
    }

    val appCtx = ctx.applicationContext
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        // applicationContext: задача живёт в статической очереди и не должна удерживать Activity
        if (uri != null) JobQueue.submit(appCtx, tr(R.string.job_exporting, dir.name)) { ctl -> Exporter(appCtx).export(uri, dir, ctl) }
    }

    val ov = overlay
    if (ov != null) {
        when (ov) {
            is Overlay.Diff -> DiffScreen(dir, ov.path, onEdit = { overlay = Overlay.Edit(ov.path) }, onClose = { overlay = null; tick++ })
            is Overlay.Edit -> EditorScreen(dir, ov.path, onClose = { overlay = null; tick++ })
        }
    } else Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(dir.name, fontWeight = FontWeight.Bold)
                    Text(branch, style = MaterialTheme.typography.labelSmall)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back)) } },
            actions = {
                IconButton(onClick = { openTerminal(ctx, dir) }) { Icon(Icons.Filled.Terminal, tr(R.string.terminal)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(tr(R.string.menu_open_in)) }, onClick = { menu = false; OpenIn.folder(ctx, dir) })
                        DropdownMenuItem(text = { Text(tr(R.string.menu_open_acode)) }, onClick = { menu = false; OpenIn.acode(ctx) })
                        DropdownMenuItem(text = { Text(tr(R.string.menu_export)) }, onClick = { menu = false; exportPicker.launch(null) })
                        DropdownMenuItem(text = { Text(tr(R.string.pin_widget)) }, onClick = {
                            menu = false
                            scope.launch {
                                val id = withContext(Dispatchers.IO) {
                                    runCatching {
                                        Git.open(dir).use { g ->
                                            g.repository.config.getString("remote", "origin", "url")?.let { vm.ops.parseGithub(it) }
                                        }
                                    }.getOrNull()
                                }
                                if (id == null) Toast.makeText(ctx, tr(R.string.ci_not_github), Toast.LENGTH_SHORT).show()
                                else {
                                    val name = id.first + "/" + id.second
                                    vm.prefs.pinnedRepo = name
                                    WidgetRefresh.launch(ctx, vm.prefs.token, force = true)
                                    Toast.makeText(ctx, tr(R.string.pinned_ok, name), Toast.LENGTH_SHORT).show()
                                }
                            }
                        })
                    }
                }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            progress?.let { p -> Column(Modifier.padding(12.dp)) { TransferCard(p) } }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> FilesTab(dir, tick, filesSub, { filesSub = it }, { overlay = Overlay.Edit(it) }) { tick++ }
                1 -> ChangesTab(vm, dir, tick, commitMsg, { commitMsg = it }, { overlay = Overlay.Diff(it) }) { tick++ }
                2 -> HistoryTab(vm, dir, tick)
                3 -> BranchesTab(vm, dir, tick) { tick++ }
                else -> ActionsTab(vm, dir, tick)
            }
        }
    }
}

@Composable
private fun FilesTab(dir: File, tick: Int, sub: String, onSub: (String) -> Unit, onOpen: (String) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val cur = if (sub.isEmpty()) dir else File(dir, sub)
    var entries by remember { mutableStateOf(listOf<File>()) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tick, sub) {
        entries = withContext(Dispatchers.IO) {
            cur.listFiles()?.filter { it.name != ".git" }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        }
    }
    BackHandler(enabled = sub.isNotEmpty()) { onSub(sub.substringBeforeLast('/', "")) }

    val appCtx = ctx.applicationContext
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) JobQueue.submit(appCtx, tr(R.string.job_add_files)) { ctl -> Importer(appCtx).importFiles(uris, cur, ctl) }
    }
    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) JobQueue.submit(appCtx, tr(R.string.job_add_folder)) { ctl -> Importer(appCtx).importTree(uri, cur, ctl) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.UploadFile, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.tab_files))
            }
            Button(onClick = { pickDir.launch(null) }) {
                Icon(Icons.Filled.DriveFolderUpload, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.btn_folder))
            }
        }
        Text(
            "/" + sub, Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
            if (entries.isEmpty()) item { Text(tr(R.string.folder_empty), Modifier.padding(16.dp)) }
            items(entries, key = { it.absolutePath }) { f ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            val rel = if (sub.isEmpty()) f.name else "$sub/${f.name}"
                            if (f.isDirectory) onSub(rel) else onOpen(rel)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (f.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null,
                        tint = if (f.isDirectory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name)
                        if (f.isFile) Text(fmtBytes(f.length()), style = MaterialTheme.typography.labelSmall)
                    }
                    if (f.isFile) IconButton(onClick = { OpenIn.file(ctx, f) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, tr(R.string.open_with)) }
                    IconButton(onClick = { toDelete = f }) { Icon(Icons.Filled.Delete, tr(R.string.delete)) }
                }
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(tr(R.string.del_title, f.name)) },
            text = { Text(tr(R.string.del_file_text)) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch { withContext(Dispatchers.IO) { f.deleteTree() }; onChanged() }
                }) { Text(tr(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(tr(R.string.cancel)) } }
        )
    }
}

@Composable
private fun ChangesTab(vm: MainVm, dir: File, tick: Int, msg: String, onMsg: (String) -> Unit, onDiff: (String) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(listOf<Pair<String, String>>()) }

    LaunchedEffect(tick) {
        rows = withContext(Dispatchers.IO) {
            try { Git.open(dir).use { vm.ops.statusRows(it) } } catch (e: Exception) { emptyList() }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                msg, { onMsg(it) }, label = { Text(tr(R.string.commit_msg)) },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { JobQueue.submit(ctx, tr(R.string.job_sending, dir.name)) { ctl -> vm.ops.send(dir, msg, ctl) } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Bolt, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.btn_send))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { Git.open(dir).use { vm.ops.commitAll(it, msg.ifBlank { "Update from GitGo" }) } }
                        }
                        val text = r.fold({ if (it) tr(R.string.committed_ok) else tr(R.string.no_changes) }, { tr(R.string.error_fmt, it.message ?: "") })
                        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                }) { Text("Commit") }
                OutlinedButton(onClick = {
                    JobQueue.submit(ctx, "Push ${dir.name}") { ctl -> Git.open(dir).use { vm.ops.push(it, ctl) } }
                }) { Text("Push") }
                OutlinedButton(onClick = {
                    JobQueue.submit(ctx, "Pull ${dir.name}") { ctl -> Git.open(dir).use { vm.ops.pull(it, ctl) } }
                }) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(4.dp)); Text("Pull") }
            }
            Text(
                if (rows.isEmpty()) tr(R.string.no_changes) else tr(R.string.changed_count, rows.size),
                style = MaterialTheme.typography.labelMedium
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(rows, key = { it.second }) { (tag, path) ->
                Row(Modifier.fillMaxWidth().clickable { onDiff(path) }.padding(vertical = 6.dp)) {
                    Text(
                        tag, Modifier.width(24.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        color = when (tag) {
                            "A", "?" -> MaterialTheme.colorScheme.primary
                            "D", "!" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.secondary
                        }
                    )
                    Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(vm: MainVm, dir: File, tick: Int) {
    var log by remember { mutableStateOf(listOf<LogRow>()) }
    LaunchedEffect(tick) {
        log = withContext(Dispatchers.IO) {
            try { Git.open(dir).use { vm.ops.log(it) } } catch (e: Exception) { emptyList() }
        }
    }
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (log.isEmpty()) item { Text(tr(R.string.no_commits)) }
        items(log) { r ->
            Column {
                Text(r.msg, fontWeight = FontWeight.Medium)
                Text(
                    "${r.id} • ${r.author} • ${fmt.format(Date(r.time))}",
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun BranchesTab(vm: MainVm, dir: File, tick: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(listOf<String>()) }
    var current by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            try { Git.open(dir).use { list = vm.ops.branches(it); current = it.repository.branch } } catch (e: Exception) { }
        }
    }

    fun act(block: (Git) -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Git.open(dir).use(block) } }
            r.exceptionOrNull()?.let { Toast.makeText(ctx, tr(R.string.error_fmt, it.message ?: ""), Toast.LENGTH_LONG).show() }
            onChanged()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Button(onClick = { showNew = true }, modifier = Modifier.padding(12.dp)) {
            Icon(Icons.Filled.AccountTree, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.new_branch))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(list) { b ->
                Row(
                    Modifier.fillMaxWidth().clickable { if (b != current) act { it.checkout().setName(b).call() } }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (b == current) "● $b" else "   $b", fontFamily = FontFamily.Monospace, fontWeight = if (b == current) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }

    if (showNew) AlertDialog(
        onDismissRequest = { showNew = false },
        title = { Text(tr(R.string.new_branch)) },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text(tr(R.string.name_label)) }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = {
                val n = newName.trim(); showNew = false; newName = ""
                act { it.checkout().setCreateBranch(true).setName(n).call() }
            }) { Text(tr(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = { showNew = false }) { Text(tr(R.string.cancel)) } }
    )
}
