package com.gitgo.app

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class RemoteRepo(
    val owner: String, val name: String, val cloneUrl: String,
    val isPrivate: Boolean, val description: String, val defaultBranch: String
)
data class Me(val login: String, val id: Long)
data class TreeEntry(val path: String, val sha: String?, val mode: String = "100644")
class FileChange(val path: String, val file: File?, val mode: String = "100644")
class ApiException(val code: Int, msg: String) : Exception(msg)
data class RunInfo(
    val id: Long, val name: String, val title: String, val status: String, val conclusion: String,
    val branch: String, val number: Int, val url: String, val event: String, val created: String
)
data class StepInfo(val number: Int, val name: String, val conclusion: String)
data class JobInfo(val id: Long, val name: String, val status: String, val conclusion: String, val steps: List<StepInfo>)
data class PrInfo(val title: String, val repo: String, val number: Int, val url: String)

class GitHubApi(private val token: String) {

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_BLOB = 100L * 1024 * 1024
        private const val BASE = "https://api.github.com"

        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .build()
        }
    }

    private class Resp(val code: Int, val body: String)

    /** Проверка отмены задачи; активные HTTP-вызовы прерываются через cancelAll(). */
    @Volatile var cancelCheck: () -> Boolean = { false }
    private val inflight: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    fun cancelAll() { inflight.forEach { it.cancel() } }

    private fun sleepCancellable(ms: Long) {
        var left = ms
        while (left > 0) {
            if (cancelCheck()) throw CancellationException(tr(R.string.cancelled))
            val step = minOf(left, 200L)
            Thread.sleep(step)
            left -= step
        }
    }

    private fun req(path: String): Request.Builder =
        Request.Builder().url(BASE + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")

    /** Запрос с повтором при 5xx / rate limit / сетевых сбоях; прерывается при отмене. */
    private fun exec(build: () -> Request): Resp {
        var attempt = 0
        while (true) {
            if (cancelCheck()) throw CancellationException(tr(R.string.cancelled))
            var waitMs = 0L
            try {
                val call = client.newCall(build())
                inflight.add(call)
                try {
                    call.execute().use { r ->
                        val body = r.body?.string() ?: ""
                        val ra = r.header("Retry-After")?.toLongOrNull() ?: 0L
                        val retriable = r.code in 500..599 || r.code == 429 ||
                            (r.code == 403 && (ra > 0 || body.contains("rate limit", ignoreCase = true)))
                        if (!retriable || attempt >= 4) return Resp(r.code, body)
                        waitMs = if (ra > 0) minOf(ra, 60L) * 1000 else 1000L shl attempt
                    }
                } finally {
                    inflight.remove(call)
                }
            } catch (e: IOException) {
                // Call.cancel() даёт IOException("Canceled") — это отмена, а не сбой сети
                if (cancelCheck()) throw CancellationException(tr(R.string.cancelled))
                if (attempt >= 4) throw e
                waitMs = 1000L shl attempt
            }
            attempt++
            sleepCancellable(waitMs)
        }
    }

    private fun check(r: Resp, what: String) {
        if (r.code in 200..299) return
        val m = try { JSONObject(r.body).optString("message") } catch (e: Exception) { r.body.take(200) }
        throw ApiException(r.code, "$what: HTTP ${r.code} $m")
    }

    private fun enc(s: String) = Uri.encode(s, "/")
    private fun jsonBody(o: JSONObject) = o.toString().toRequestBody(JSON)

    fun me(): Me {
        val r = exec { req("/user").get().build() }
        check(r, tr(R.string.api_what_login))
        val j = JSONObject(r.body)
        return Me(j.getString("login"), j.getLong("id"))
    }

    fun listRepos(): List<RemoteRepo> {
        val out = ArrayList<RemoteRepo>()
        for (page in 1..10) {
            val r = exec {
                req("/user/repos?per_page=100&sort=updated&page=$page&affiliation=owner,collaborator,organization_member").get().build()
            }
            check(r, tr(R.string.api_what_list))
            val a = JSONArray(r.body)
            for (i in 0 until a.length()) out += parseRepo(a.getJSONObject(i))
            if (a.length() < 100) break
        }
        return out
    }

    private fun parseRepo(o: JSONObject) = RemoteRepo(
        owner = o.getJSONObject("owner").getString("login"),
        name = o.getString("name"),
        cloneUrl = o.getString("clone_url"),
        isPrivate = o.getBoolean("private"),
        description = if (o.isNull("description")) "" else o.getString("description"),
        defaultBranch = if (o.isNull("default_branch")) "main" else o.getString("default_branch")
    )

    fun createRepo(name: String, priv: Boolean, desc: String): RemoteRepo {
        val b = JSONObject().put("name", name).put("private", priv).put("description", desc)
        val r = exec { req("/user/repos").post(jsonBody(b)).build() }
        check(r, tr(R.string.api_what_create))
        return parseRepo(JSONObject(r.body))
    }

    fun getRef(owner: String, repo: String, branch: String): String? {
        val r = exec { req("/repos/$owner/$repo/git/ref/heads/${enc(branch)}").get().build() }
        if (r.code == 404 || r.code == 409) return null
        check(r, tr(R.string.api_what_branch))
        return JSONObject(r.body).getJSONObject("object").getString("sha")
    }

    private fun commitTree(owner: String, repo: String, sha: String): String {
        val r = exec { req("/repos/$owner/$repo/git/commits/$sha").get().build() }
        check(r, tr(R.string.api_what_commit))
        return JSONObject(r.body).getJSONObject("tree").getString("sha")
    }

    private fun createBlob(owner: String, repo: String, file: File, ctl: Ctl, onBytes: (Long) -> Unit): String {
        val r = exec { req("/repos/$owner/$repo/git/blobs").post(BlobBody(file, ctl, onBytes)).build() }
        check(r, tr(R.string.api_what_upload, file.name))
        return JSONObject(r.body).getString("sha")
    }

    private fun createTree(owner: String, repo: String, base: String?, entries: List<TreeEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().put("path", e.path).put("mode", e.mode).put("type", "blob")
                    .put("sha", e.sha ?: JSONObject.NULL)
            )
        }
        val body = JSONObject().put("tree", arr)
        if (base != null) body.put("base_tree", base)
        val r = exec { req("/repos/$owner/$repo/git/trees").post(jsonBody(body)).build() }
        check(r, tr(R.string.api_what_tree))
        return JSONObject(r.body).getString("sha")
    }

    private fun createCommit(
        owner: String, repo: String, msg: String, tree: String, parent: String, name: String, email: String
    ): String {
        val b = JSONObject().put("message", msg).put("tree", tree).put("parents", JSONArray().put(parent))
        if (name.isNotBlank() && email.isNotBlank()) {
            val a = JSONObject().put("name", name).put("email", email)
            b.put("author", a).put("committer", a)
        }
        val r = exec { req("/repos/$owner/$repo/git/commits").post(jsonBody(b)).build() }
        check(r, tr(R.string.api_what_commit))
        return JSONObject(r.body).getString("sha")
    }

    private fun updateRef(owner: String, repo: String, branch: String, sha: String) {
        val b = JSONObject().put("sha", sha).put("force", false)
        val r = exec { req("/repos/$owner/$repo/git/refs/heads/${enc(branch)}").patch(jsonBody(b)).build() }
        check(r, tr(R.string.api_what_ref))
    }

    private fun JSONObject.s(key: String): String = if (has(key) && !isNull(key)) getString(key) else ""

    // ───────────── GitHub Actions ─────────────

    fun listRuns(owner: String, repo: String): List<RunInfo> {
        val r = exec { req("/repos/$owner/$repo/actions/runs?per_page=30").get().build() }
        check(r, "Actions")
        val a = JSONObject(r.body).getJSONArray("workflow_runs")
        val out = ArrayList<RunInfo>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out += RunInfo(
                o.getLong("id"), o.s("name"), o.s("display_title").ifBlank { o.s("name") },
                o.s("status"), o.s("conclusion"), o.s("head_branch"), o.optInt("run_number"),
                o.s("html_url"), o.s("event"), o.s("created_at")
            )
        }
        return out
    }

    fun listJobs(owner: String, repo: String, runId: Long): List<JobInfo> {
        val r = exec { req("/repos/$owner/$repo/actions/runs/$runId/jobs?per_page=50").get().build() }
        check(r, "Actions")
        val a = JSONObject(r.body).getJSONArray("jobs")
        val out = ArrayList<JobInfo>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val steps = ArrayList<StepInfo>()
            o.optJSONArray("steps")?.let { sa ->
                for (k in 0 until sa.length()) {
                    val so = sa.getJSONObject(k)
                    steps += StepInfo(so.optInt("number"), so.s("name"), so.s("conclusion"))
                }
            }
            out += JobInfo(o.getLong("id"), o.s("name"), o.s("status"), o.s("conclusion"), steps)
        }
        return out
    }

    /** Лог задачи (GitHub отдаёт редирект на файл — OkHttp идёт по нему сам). Возвращает хвост до 200 000 символов. */
    fun jobLogs(owner: String, repo: String, jobId: Long): String {
        val r = exec { req("/repos/$owner/$repo/actions/jobs/$jobId/logs").get().build() }
        if (r.code == 404 || r.code == 410) return ""
        check(r, "Logs")
        return if (r.body.length > 200_000) r.body.takeLast(200_000) else r.body
    }

    fun rerunFailed(owner: String, repo: String, runId: Long) {
        val r = exec { req("/repos/$owner/$repo/actions/runs/$runId/rerun-failed-jobs").post("".toRequestBody(JSON)).build() }
        check(r, "Re-run")
    }

    fun rerunAll(owner: String, repo: String, runId: Long) {
        val r = exec { req("/repos/$owner/$repo/actions/runs/$runId/rerun").post("".toRequestBody(JSON)).build() }
        check(r, "Re-run")
    }

    fun cancelRun(owner: String, repo: String, runId: Long) {
        val r = exec { req("/repos/$owner/$repo/actions/runs/$runId/cancel").post("".toRequestBody(JSON)).build() }
        check(r, "Cancel")
    }

    // ───────────── Виджеты ─────────────

    /** Календарь вкладов: (всего, недели → дни). */
    fun contributions(): Pair<Int, List<List<Int>>> {
        val q = "{ viewer { contributionsCollection { contributionCalendar { totalContributions weeks { contributionDays { contributionCount } } } } } }"
        val r = exec { req("/graphql").post(jsonBody(JSONObject().put("query", q))).build() }
        check(r, "GraphQL")
        val j = JSONObject(r.body)
        if (!j.has("data") || j.isNull("data")) throw ApiException(r.code, "GraphQL: " + j.optJSONArray("errors")?.optJSONObject(0)?.s("message"))
        val cal = j.getJSONObject("data").getJSONObject("viewer")
            .getJSONObject("contributionsCollection").getJSONObject("contributionCalendar")
        val weeks = ArrayList<List<Int>>()
        val wa = cal.getJSONArray("weeks")
        for (i in 0 until wa.length()) {
            val da = wa.getJSONObject(i).getJSONArray("contributionDays")
            weeks += (0 until da.length()).map { da.getJSONObject(it).optInt("contributionCount") }
        }
        return cal.optInt("totalContributions") to weeks
    }

    fun reviewRequests(): List<PrInfo> {
        val q = Uri.encode("is:pr is:open review-requested:@me archived:false")
        val r = exec { req("/search/issues?q=$q&per_page=5").get().build() }
        check(r, "Reviews")
        val a = JSONObject(r.body).getJSONArray("items")
        val out = ArrayList<PrInfo>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out += PrInfo(o.s("title"), o.s("repository_url").substringAfter("/repos/"), o.optInt("number"), o.s("html_url"))
        }
        return out
    }

    // ───────────── SSH-ключи ─────────────

    fun addSshKey(title: String, key: String) {
        val r = exec { req("/user/keys").post(jsonBody(JSONObject().put("title", title).put("key", key))).build() }
        check(r, "SSH")
    }

    /**
     * Быстрая отправка: параллельная загрузка blob-ов, одно дерево, один коммит, одно обновление ветки.
     * Файлы читаются потоком и кодируются в base64 «на лету» — память почти не расходуется.
     */
    suspend fun fastSend(
        owner: String, repo: String, branch: String, parent: String,
        changes: List<FileChange>, message: String, name: String, email: String, ctl: Ctl
    ): String {
        val uploads = changes.filter { it.file != null }
        val total = uploads.sumOf { it.file!!.length() }
        uploads.firstOrNull { it.file!!.length() > MAX_BLOB }?.let {
            throw ApiException(0, tr(R.string.api_too_big, it.path))
        }
        val done = AtomicLong(0)
        val meter = SpeedMeter()
        val shaMap = java.util.concurrent.ConcurrentHashMap<String, String>()
        val small = Semaphore(6)
        val large = Semaphore(2)
        ctl.report(meter.make(tr(R.string.api_sending), 0, total, "B"))

        cancelCheck = ctl.cancelled
        coroutineScope {
            // Сторож: при отмене рвёт все активные HTTP-вызовы, не дожидаясь окончания блоков
            val watcher = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(150)
                    if (ctl.cancelled()) { cancelAll(); break }
                }
            }
            try {
            uploads.map { c ->
                async(Dispatchers.IO) {
                    val f = c.file!!
                    val sem = if (f.length() > 8_000_000) large else small
                    sem.withPermit {
                        ensureActive()
                        val sha = createBlob(owner, repo, f, ctl) { n ->
                            val d = done.addAndGet(n)
                            ctl.report(meter.make(tr(R.string.api_sending), minOf(d, total), total, "B"))
                        }
                        shaMap[c.path] = sha
                    }
                }
            }.awaitAll()
            } finally {
                watcher.cancel()
            }
        }

        ctl.report(Progress(tr(R.string.api_committing), total, total, 0.0, -1, "B"))
        val entries = changes.map { TreeEntry(it.path, shaMap[it.path], it.mode) }
        var baseTree = commitTree(owner, repo, parent)
        for (chunk in entries.chunked(1000)) baseTree = createTree(owner, repo, baseTree, chunk)
        val commit = createCommit(owner, repo, message, baseTree, parent, name, email)
        updateRef(owner, repo, branch, commit)
        return commit
    }
}

private class BlobBody(
    private val file: File,
    private val ctl: Ctl,
    private val onBytes: (Long) -> Unit
) : RequestBody() {
    private val head = "{\"encoding\":\"base64\",\"content\":\""
    private val tail = "\"}"

    override fun contentType() = GitHubApi.JSON
    override fun contentLength(): Long = head.length + 4L * ((file.length() + 2) / 3) + tail.length

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(head)
        FileInputStream(file).use { ins ->
            val buf = ByteArray(3 * 87381) // кратно 3 — base64 без «хвостов» между блоками
            while (true) {
                var n = 0
                while (n < buf.size) {
                    val r = ins.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n == 0) break
                if (ctl.cancelled()) throw CancellationException(tr(R.string.cancelled))
                sink.writeUtf8(Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
                onBytes(n.toLong())
                if (n < buf.size) break
            }
        }
        sink.writeUtf8(tail)
    }
}
