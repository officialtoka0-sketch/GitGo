package com.gitgo.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import java.io.File

private val Green = Color(0xFF2EA043)
private val Red = Color(0xFFD73A49)
private val Amber = Color(0xFFD9A400)
private val Grey = Color(0xFF8A8F98)

/** Статус запуска/задачи → (иконка, цвет, подпись). */
private fun statusOf(status: String, conclusion: String): Triple<ImageVector, Color, Int> = when {
    status == "completed" && conclusion == "success" -> Triple(Icons.Filled.CheckCircle, Green, R.string.ci_success)
    status == "completed" && (conclusion == "failure" || conclusion == "timed_out" || conclusion == "startup_failure") ->
        Triple(Icons.Filled.Error, Red, R.string.ci_failure)
    status == "completed" && conclusion == "cancelled" -> Triple(Icons.Filled.Cancel, Grey, R.string.ci_cancelled)
    status == "in_progress" -> Triple(Icons.Filled.Schedule, Amber, R.string.ci_running)
    status == "queued" || status == "waiting" || status == "pending" -> Triple(Icons.Filled.Schedule, Grey, R.string.ci_queued)
    else -> Triple(Icons.Filled.HelpOutline, Grey, R.string.ci_other)
}

private sealed interface CiView {
    data object Runs : CiView
    data class Run(val run: RunInfo) : CiView
    data class Log(val run: RunInfo, val job: JobInfo) : CiView
}

@Composable
fun ActionsTab(vm: MainVm, dir: File, tick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var repoId by remember { mutableStateOf<Pair<String, String>?>(null) }
    var checked by remember { mutableStateOf(false) }
    var runs by remember { mutableStateOf(listOf<RunInfo>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<CiView>(CiView.Runs) }
    var reload by remember { mutableIntStateOf(0) }

    BackHandler(enabled = view != CiView.Runs) {
        view = when (val v = view) { is CiView.Log -> CiView.Run(v.run); else -> CiView.Runs }
    }

    LaunchedEffect(dir) {
        repoId = withContext(Dispatchers.IO) {
            runCatching {
                Git.open(dir).use { g -> g.repository.config.getString("remote", "origin", "url")?.let { vm.ops.parseGithub(it) } }
            }.getOrNull()
        }
        checked = true
    }

    LaunchedEffect(repoId, reload, tick, vm.locked) {
        val id = repoId ?: return@LaunchedEffect
        if (vm.prefs.token.isBlank()) return@LaunchedEffect
        loading = true; error = null
        try {
            runs = withContext(Dispatchers.IO) { GitHubApi(vm.prefs.token).listRuns(id.first, id.second) }
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    val id = repoId
    when {
        !checked -> {}
        id == null -> Text(tr(R.string.ci_not_github), Modifier.padding(16.dp))
        vm.prefs.token.isBlank() -> Text(tr(R.string.ci_need_login), Modifier.padding(16.dp))
        else -> when (val v = view) {
            CiView.Runs -> RunList(runs, loading, error, { reload++ }) { view = CiView.Run(it) }
            is CiView.Run -> RunDetail(vm, id, v.run, onOpenJob = { view = CiView.Log(v.run, it) }, onChanged = { reload++; view = CiView.Runs })
            is CiView.Log -> LogView(vm, id, v.job)
        }
    }
}

@Composable
private fun RunList(runs: List<RunInfo>, loading: Boolean, error: String?, onRefresh: () -> Unit, onOpen: (RunInfo) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.tab_ci), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, tr(R.string.refresh)) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (runs.isEmpty() && !loading && error == null) item { Text(tr(R.string.ci_none)) }
            items(runs, key = { it.id }) { r ->
                val (icon, color, label) = statusOf(r.status, r.conclusion)
                Card(Modifier.fillMaxWidth().clickable { onOpen(r) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, tr(label), tint = color)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, fontWeight = FontWeight.Medium, maxLines = 2)
                            Text("#${r.number} • ${r.branch} • ${r.event} • ${tr(label)}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunDetail(
    vm: MainVm, id: Pair<String, String>, run: RunInfo,
    onOpenJob: (JobInfo) -> Unit, onChanged: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var jobs by remember { mutableStateOf(listOf<JobInfo>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val (icon, color, label) = statusOf(run.status, run.conclusion)

    LaunchedEffect(run.id, reload) {
        loading = true; error = null
        try {
            jobs = withContext(Dispatchers.IO) { GitHubApi(vm.prefs.token).listJobs(id.first, id.second, run.id) }
        } catch (e: Exception) { error = e.message } finally { loading = false }
    }

    fun act(block: (GitHubApi) -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block(GitHubApi(vm.prefs.token)) } }
            val msg = r.exceptionOrNull()?.let { tr(R.string.error_fmt, it.message ?: "") } ?: tr(R.string.ci_rerun_started)
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            reload++; onChanged()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, tr(label), tint = color)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(run.title, fontWeight = FontWeight.Bold)
                    Text("#${run.number} • ${run.branch} • ${tr(label)}", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { uri.openUri(run.url) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, tr(R.string.ci_open_web)) }
            }
        }
        item {
            val failed = run.status == "completed" && run.conclusion != "success" && run.conclusion != "cancelled" || jobs.any { it.conclusion == "failure" }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { act { it.rerunFailed(id.first, id.second, run.id) } }, enabled = failed && run.status == "completed") {
                    Text(tr(R.string.ci_rerun_failed))
                }
            }
            Spacer(Modifier.padding(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { act { it.rerunAll(id.first, id.second, run.id) } }, enabled = run.status == "completed") {
                    Text(tr(R.string.ci_rerun_all))
                }
                if (run.status != "completed") OutlinedButton(onClick = { act { it.cancelRun(id.first, id.second, run.id) } }) {
                    Text(tr(R.string.ci_cancel_run))
                }
            }
        }
        item { Text(tr(R.string.ci_jobs), style = MaterialTheme.typography.titleMedium) }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
        items(jobs, key = { it.id }) { j ->
            val (ji, jc, jl) = statusOf(j.status, j.conclusion)
            val failedSteps = j.steps.filter { it.conclusion == "failure" }.joinToString { it.name }
            Card(Modifier.fillMaxWidth().clickable { onOpenJob(j) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ji, tr(jl), tint = jc)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(j.name, fontWeight = FontWeight.Medium)
                        if (failedSteps.isNotBlank()) Text(
                            tr(R.string.ci_failed_steps, failedSteps),
                            style = MaterialTheme.typography.labelSmall, color = Red
                        )
                    }
                }
            }
        }
    }
}

private val tsRegex = Regex("^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z ?")

@Composable
private fun LogView(vm: MainVm, id: Pair<String, String>, job: JobInfo) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(job.id) {
        try {
            val txt = withContext(Dispatchers.IO) { GitHubApi(vm.prefs.token).jobLogs(id.first, id.second, job.id) }
            lines = txt.lines().map { tsRegex.replace(it, "") }
        } catch (e: Exception) { error = e.message; lines = emptyList() }
    }

    fun isError(l: String) = l.contains("##[error]") || l.contains("Error:", ignoreCase = false) || l.contains("error:", ignoreCase = false)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(job.name, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1)
            val ls = lines
            if (ls != null && ls.isNotEmpty()) {
                OutlinedButton(onClick = {
                    val i = ls.indexOfFirst { isError(it) }
                    if (i >= 0) scope.launch { listState.scrollToItem(i) }
                }) { Text(tr(R.string.ci_first_error), fontSize = 12.sp) }
                IconButton(onClick = {
                    copyText(ctx, "log", ls.joinToString("\n"))
                    Toast.makeText(ctx, tr(R.string.ci_copied), Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Filled.ContentCopy, tr(R.string.ci_copy)) }
            }
        }
        val ls = lines
        when {
            ls == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ls.isEmpty() || ls.all { it.isBlank() } -> Text(error ?: tr(R.string.ci_log_empty), Modifier.padding(16.dp))
            else -> Box(Modifier.fillMaxSize().background(Color(0xFF0B0F0B)).horizontalScroll(rememberScrollState())) {
                LazyColumn(state = listState, contentPadding = PaddingValues(8.dp)) {
                    items(ls.size) { i ->
                        val l = ls[i]
                        val c = when {
                            isError(l) -> Color(0xFFFF6B6B)
                            l.contains("##[warning]") -> Color(0xFFFFC857)
                            l.contains("##[group]") -> Color(0xFF7AB8F5)
                            else -> Color(0xFFD6F5D6)
                        }
                        Text(
                            l.removePrefix("##[group]").removePrefix("##[error]"),
                            color = c, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false,
                            fontWeight = if (l.contains("##[group]")) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
