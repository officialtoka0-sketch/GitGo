package com.gitgo.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File

data class Pending(val upload: List<String>, val delete: List<String>) {
    val count: Int get() = upload.size + delete.size
}
data class LogRow(val id: String, val msg: String, val author: String, val time: Long)

/** Мост между JGit и нашим прогрессом. */
class Monitor(private val title: String, private val ctl: Ctl) : ProgressMonitor {
    private var task = ""
    private var total = 0
    private var done = 0
    private val meter = SpeedMeter()
    private var last = 0L

    override fun start(totalTasks: Int) {}
    override fun beginTask(title: String?, totalWork: Int) {
        task = title ?: ""; total = totalWork; done = 0; meter.reset()
    }
    override fun update(completed: Int) {
        done += completed
        val now = System.currentTimeMillis()
        if (now - last > 250) {
            last = now
            ctl.report(meter.make("${this.title}: $task", done.toLong(), total.toLong(), "obj"))
        }
    }
    override fun endTask() {}
    override fun isCancelled(): Boolean = ctl.cancelled()
}

class GitOps(ctx: Context, private val prefs: Prefs) {

    // Внутренняя память: у «общего» хранилища (FUSE) нет прав доступа и символьных ссылок, а имена нечувствительны к регистру.
    val root: File = File(ctx.filesDir, "repos").also { it.mkdirs() }

    fun creds(): CredentialsProvider? =
        if (prefs.token.isBlank()) null else UsernamePasswordCredentialsProvider("x-access-token", prefs.token)

    fun author(): PersonIdent {
        val n = prefs.userName.ifBlank { prefs.login.ifBlank { "GitGo" } }
        val e = prefs.userEmail.ifBlank {
            if (prefs.login.isNotBlank()) "${prefs.userId}+${prefs.login}@users.noreply.github.com"
            else "gitgo@users.noreply.github.com"
        }
        return PersonIdent(n, e)
    }

    fun repos(): List<File> =
        root.listFiles { f -> f.isDirectory && File(f, ".git").exists() }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun uniqueDir(name: String): File {
        val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "repo" }
        var d = File(root, clean)
        var i = 2
        while (d.exists()) { d = File(root, "$clean-$i"); i++ }
        return d
    }

    fun cloneRepo(url: String, name: String, defaultBranch: String, ctl: Ctl): File {
        val dir = uniqueDir(name)
        try {
            Git.cloneRepository().setURI(url).setDirectory(dir)
                .setCredentialsProvider(creds())
                .setProgressMonitor(Monitor(tr(R.string.op_cloning, name), ctl))
                .call().use { git ->
                    // Пустой репозиторий: направляем HEAD на ветку по умолчанию GitHub и настраиваем upstream.
                    if (git.repository.resolve("HEAD") == null) {
                        git.repository.updateRef(Constants.HEAD).link("refs/heads/$defaultBranch")
                        val cfg = git.repository.config
                        cfg.setString("branch", defaultBranch, "remote", "origin")
                        cfg.setString("branch", defaultBranch, "merge", "refs/heads/$defaultBranch")
                        cfg.save()
                    }
                }
        } catch (e: Exception) {
            dir.deleteTree()
            throw e
        }
        return dir
    }

    /** Отключаем поиск дельт и ставим быстрое сжатие — push заметно быстрее на телефоне. */
    private fun tune(git: Git) {
        val c = git.repository.config
        c.setBoolean("pack", null, "deltacompression", false)
        c.setInt("pack", null, "compression", 1)
        c.setInt("pack", null, "threads", Runtime.getRuntime().availableProcessors())
    }

    fun push(git: Git, ctl: Ctl): String {
        tune(git)
        val branch = git.repository.fullBranch
        val results = git.push().setRemote("origin").add(branch)
            .setCredentialsProvider(creds())
            .setProgressMonitor(Monitor("Push", ctl))
            .call()
        for (r in results) for (u in r.remoteUpdates) {
            when (u.status) {
                RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> {}
                else -> throw IllegalStateException(tr(R.string.push_rejected, u.status.toString(), u.message ?: ""))
            }
        }
        return tr(R.string.push_done)
    }

    fun pull(git: Git, ctl: Ctl): String {
        val r = git.pull().setCredentialsProvider(creds())
            .setProgressMonitor(Monitor("Pull", ctl)).call()
        if (!r.isSuccessful) throw IllegalStateException(tr(R.string.pull_failed))
        return tr(R.string.pull_done)
    }

    fun fetch(git: Git, ctl: Ctl): String {
        git.fetch().setRemote("origin").setCredentialsProvider(creds())
            .setProgressMonitor(Monitor("Fetch", ctl)).call()
        return tr(R.string.fetch_done)
    }

    fun commitAll(git: Git, msg: String): Boolean {
        if (git.status().call().isClean) return false
        git.add().addFilepattern(".").call()
        git.add().setUpdate(true).addFilepattern(".").call()
        val a = author()
        git.commit().setMessage(msg).setAuthor(a).setCommitter(a).call()
        return true
    }

    fun pending(git: Git): Pending {
        val s = git.status().call()
        val up = (s.added + s.changed + s.modified + s.untracked).toSortedSet().toList()
        val del = (s.missing + s.removed).toSortedSet().toList()
        return Pending(up, del)
    }

    fun statusRows(git: Git): List<Pair<String, String>> {
        val s = git.status().call()
        val r = ArrayList<Pair<String, String>>()
        s.added.forEach { r += "A" to it }
        s.untracked.forEach { r += "?" to it }
        s.changed.forEach { r += "M" to it }
        s.modified.forEach { r += "M" to it }
        s.removed.forEach { r += "D" to it }
        s.missing.forEach { r += "D" to it }
        s.conflicting.forEach { r += "!" to it }
        return r.distinctBy { it.second }.sortedBy { it.second }
    }

    fun log(git: Git, n: Int = 100): List<LogRow> = try {
        git.log().setMaxCount(n).call().map {
            LogRow(it.name.take(7), it.shortMessage, it.authorIdent.name, it.commitTime * 1000L)
        }
    } catch (e: NoHeadException) {
        emptyList()
    }

    fun branches(git: Git): List<String> =
        git.branchList().call().map { Repository.shortenRefName(it.name) }

    fun parseGithub(url: String): Pair<String, String>? {
        val m = Regex("github\\.com[:/]+([^/]+)/([^/]+?)(\\.git)?/?$").find(url) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    private fun modeOf(git: Git, path: String): String = try {
        val tree = git.repository.resolve("HEAD^{tree}")
        if (tree == null) "100644"
        else TreeWalk.forPath(git.repository, path, tree)?.use {
            if (it.getFileMode(0) == FileMode.EXECUTABLE_FILE) "100755" else "100644"
        } ?: "100644"
    } catch (e: Exception) {
        "100644"
    }

    /**
     * «Умная» отправка: коммит + push. В режиме API/AUTO изменения уходят параллельно через GitHub API
     * одним коммитом; иначе — обычный git push (пакет). Возвращает итоговое сообщение.
     */
    suspend fun send(dir: File, message: String, ctl: Ctl): String {
        val t0 = System.nanoTime()
        Git.open(dir).use { git ->
            val msg = message.ifBlank { "Update from GitGo" }
            val pend = pending(git)
            val url = git.repository.config.getString("remote", "origin", "url")
            val gh = url?.let { parseGithub(it) }
            val branch = git.repository.branch
            val mode = prefs.sendMode

            var useApi = gh != null && prefs.token.isNotBlank() && pend.count > 0 &&
                when (mode) { "GIT" -> false; "API" -> true; else -> pend.count <= 150 }

            val api = GitHubApi(prefs.token).also { it.cancelCheck = ctl.cancelled }
            if (useApi && gh != null) {
                val remoteSha = api.getRef(gh.first, gh.second, branch)
                val head = git.repository.resolve("HEAD")?.name
                if (remoteSha == null || remoteSha != head) useApi = false
            }

            // Список файлов для API строим заранее: если реально отправлять нечего (например, файл удалён после
            // git add), пустое дерево GitHub отклонит — тогда идём обычным путём.
            val changes = ArrayList<FileChange>()
            var bytes = 0L
            if (useApi) {
                for (p in pend.upload) {
                    val f = File(dir, p)
                    // символьные ссылки не отправляем: API прочитал бы файл, на который они указывают
                    if (f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())) { bytes += f.length(); changes += FileChange(p, f, modeOf(git, p)) }
                }
                for (p in pend.delete) changes += FileChange(p, null)
                if (changes.isEmpty()) useApi = false
            }

            if (useApi && gh != null) {
                val head = git.repository.resolve("HEAD")!!.name
                val a = author()
                api.fastSend(gh.first, gh.second, branch, head, changes, msg, a.name, a.emailAddress, ctl)
                ctl.report(Progress(tr(R.string.syncing), 0, 0))
                git.fetch().setRemote("origin").setCredentialsProvider(creds()).call()
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/$branch").call()
                val sec = (System.nanoTime() - t0) / 1e9
                val avg = if (sec > 0) (bytes / sec).toLong() else 0L
                return tr(
                    R.string.send_api_done, pend.upload.size, pend.delete.size, fmtBytes(bytes),
                    String.format(java.util.Locale.US, "%.1f", sec), fmtBytes(avg)
                )
            }

            val committed = commitAll(git, msg)
            push(git, ctl)
            val sec = (System.nanoTime() - t0) / 1e9
            val secStr = String.format(java.util.Locale.US, "%.1f", sec)
            return tr(if (committed) R.string.send_git_committed else R.string.send_git_nochange, secStr)
        }
    }
}
