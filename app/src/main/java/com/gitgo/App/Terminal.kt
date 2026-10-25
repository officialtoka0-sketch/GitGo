package com.gitgo.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import java.io.ByteArrayOutputStream
import java.io.File

const val CLEAR = "\u0000CLEAR"

/**
 * Встроенная git-консоль (не полноценный Linux-шелл): работает только внутри папки репозиториев GitGo.
 * Команды git выполняются через JGit, поэтому внешний бинарник git не нужен.
 */
class GitTerminal(private val ops: GitOps, private val prefs: Prefs, start: File?) {

    private val rootPath: String = ops.root.canonicalPath
    var cwd: File = (start?.takeIf { it.exists() } ?: ops.root).canonicalFile

    fun prompt(): String = "gitgo:${relName(cwd)}$"

    private fun relName(f: File): String {
        val p = f.canonicalPath
        return if (p == rootPath) "~" else "~" + p.removePrefix(rootPath)
    }

    private fun resolve(p: String): File {
        val base = if (p.startsWith("/")) File(rootPath, p) else File(cwd, p)
        val f = base.canonicalFile
        if (f.path != rootPath && !f.path.startsWith("$rootPath/"))
            throw IllegalArgumentException(tr(R.string.t_outside))
        return f
    }

    private fun repoRoot(): File {
        var d: File? = cwd
        while (d != null && (d.path == rootPath || d.path.startsWith("$rootPath/"))) {
            if (File(d, ".git").exists()) return d
            d = d.parentFile
        }
        throw IllegalStateException(tr(R.string.t_not_repo))
    }

    private inline fun <T> withGit(block: (Git) -> T): T = Git.open(repoRoot()).use(block)

    private fun arg(t: List<String>, i: Int): String =
        t.getOrNull(i) ?: throw IllegalArgumentException(tr(R.string.t_missing_arg))

    fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q: Char? = null
        var has = false
        for (c in s) {
            if (q != null) {
                if (c == q) q = null else sb.append(c)
            } else if (c == '"' || c == '\'') {
                q = c; has = true
            } else if (c.isWhitespace()) {
                if (sb.isNotEmpty() || has) { out += sb.toString(); sb.clear(); has = false }
            } else sb.append(c)
        }
        if (sb.isNotEmpty() || has) out += sb.toString()
        return out
    }

    suspend fun run(line: String, emit: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val t = tokenize(line)
        if (t.isEmpty()) return@withContext ""
        try {
            when (t[0]) {
                "help" -> tr(R.string.term_help)
                "clear", "cls" -> CLEAR
                "pwd" -> relName(cwd)
                "ls", "ll" -> ls(t.getOrNull(1))
                "cd" -> cd(t.getOrNull(1))
                "cat" -> resolve(arg(t, 1)).inputStream().use { ins ->
                    // читаем не больше 100 КБ: огромный файл не должен убить приложение по памяти
                    val buf = ByteArray(100_000)
                    String(buf, 0, ins.read(buf).coerceAtLeast(0))
                }
                "mkdir" -> { resolve(arg(t, 1)).mkdirs(); "" }
                "touch" -> { val f = resolve(arg(t, 1)); f.parentFile?.mkdirs(); f.createNewFile(); "" }
                "rm" -> rm(t.drop(1))
                "echo" -> t.drop(1).joinToString(" ")
                "git" -> git(t.drop(1), emit)
                else -> tr(R.string.t_not_found, t[0])
            }
        } catch (e: Exception) {
            tr(R.string.t_error, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun ls(p: String?): String {
        val d = if (p == null) cwd else resolve(p)
        if (!d.isDirectory) return d.name
        val l = d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        return if (l.isEmpty()) tr(R.string.t_empty) else l.joinToString("\n") { if (it.isDirectory) it.name + "/" else it.name }
    }

    private fun cd(p: String?): String {
        val d = if (p == null || p == "~") ops.root else resolve(p)
        if (!d.isDirectory) throw IllegalArgumentException(tr(R.string.t_no_dir))
        cwd = d
        return ""
    }

    private fun rm(a: List<String>): String {
        val rec = a.any { it.startsWith("-") && it.contains('r') }
        for (p in a.filter { !it.startsWith("-") }) {
            val f = resolve(p)
            if (f.path == rootPath) throw IllegalArgumentException(tr(R.string.t_no_rm_root))
            if (f.isDirectory && !rec) throw IllegalArgumentException(tr(R.string.t_rm_dir, f.name))
            f.deleteTree()
        }
        return ""
    }

    private fun ctl(emit: (String) -> Unit): Ctl {
        var last = 0L
        var lastTitle = ""
        return Ctl({ p ->
            val now = System.currentTimeMillis()
            if (p.title != lastTitle || now - last > 800 || p.percent == 100) {
                lastTitle = p.title; last = now
                emit(p.title + if (p.percent >= 0) " ${p.percent}%" else "")
            }
        }, { false })
    }

    private fun status(g: Git): String {
        val s = g.status().call()
        val sb = StringBuilder(tr(R.string.t_on_branch, g.repository.branch) + "\n")
        if (s.isClean) return sb.append(tr(R.string.t_clean)).toString()
        fun sec(title: String, set: Set<String>, tag: String) {
            if (set.isNotEmpty()) {
                sb.append("\n$title:\n")
                set.sorted().forEach { sb.append("  $tag $it\n") }
            }
        }
        sec(tr(R.string.t_staged), s.added, tr(R.string.t_tag_new).padEnd(10))
        sec(tr(R.string.t_staged), s.changed, tr(R.string.t_tag_mod).padEnd(10))
        sec(tr(R.string.t_staged), s.removed, tr(R.string.t_tag_del).padEnd(10))
        sec(tr(R.string.t_unstaged), s.modified, tr(R.string.t_tag_mod).padEnd(10))
        sec(tr(R.string.t_unstaged), s.missing, tr(R.string.t_tag_del).padEnd(10))
        sec(tr(R.string.t_untracked), s.untracked, "?        ")
        sec(tr(R.string.t_conflicts), s.conflicting, "!        ")
        return sb.toString().trimEnd()
    }

    private fun git(a: List<String>, emit: (String) -> Unit): String {
        val sub = a.firstOrNull() ?: return tr(R.string.t_usage)
        val rest = a.drop(1)
        return when (sub) {
            "init" -> {
                Git.init().setDirectory(cwd).setInitialBranch("main").call().close()
                tr(R.string.t_init, relName(cwd))
            }
            "clone" -> {
                val url = arg(rest, 0)
                val name = rest.getOrNull(1)
                    ?: url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
                val dir = File(cwd, name).canonicalFile
                if (!dir.path.startsWith("$rootPath/")) throw IllegalArgumentException(tr(R.string.t_outside))
                if (dir.exists()) throw IllegalStateException(tr(R.string.t_dir_exists, name))
                try {
                    Git.cloneRepository().setURI(url).setDirectory(dir).setCredentialsProvider(ops.creds())
                        .setProgressMonitor(Monitor(tr(R.string.t_cloning), ctl(emit))).call().close()
                } catch (e: Exception) {
                    dir.deleteTree(); throw e
                }
                tr(R.string.t_cloned, relName(dir))
            }
            "status" -> withGit { status(it) }
            "add" -> withGit { g ->
                val root = repoRoot()
                val pats = rest.filter { !it.startsWith("-") }.ifEmpty { listOf(".") }
                for (p in pats) {
                    val rel = if (p == ".") cwd.relativeTo(root).path.ifEmpty { "." }
                    else resolve(p).relativeTo(root).path.ifEmpty { "." }
                    g.add().addFilepattern(rel).call()
                    g.add().setUpdate(true).addFilepattern(rel).call()
                }
                ""
            }
            "commit" -> withGit { g ->
                var msg: String? = null
                var all = false
                var i = 0
                while (i < rest.size) {
                    when (rest[i]) {
                        "-m" -> { msg = rest.getOrNull(i + 1); i++ }
                        "-a" -> all = true
                        "-am" -> { all = true; msg = rest.getOrNull(i + 1); i++ }
                    }
                    i++
                }
                if (msg.isNullOrBlank()) throw IllegalArgumentException(tr(R.string.t_need_msg))
                if (all) {
                    g.add().addFilepattern(".").call()
                    g.add().setUpdate(true).addFilepattern(".").call()
                }
                val au = ops.author()
                val c = g.commit().setMessage(msg).setAuthor(au).setCommitter(au).call()
                "[${g.repository.branch} ${c.name.take(7)}] ${c.shortMessage}"
            }
            "push" -> withGit { ops.push(it, ctl(emit)) }
            "pull" -> withGit { ops.pull(it, ctl(emit)) }
            "fetch" -> withGit { ops.fetch(it, ctl(emit)) }
            "log" -> withGit { g ->
                val n = rest.firstOrNull { it.startsWith("-") && it.drop(1).all { ch -> ch.isDigit() } && it.length > 1 }
                    ?.drop(1)?.toInt() ?: 20
                val l = ops.log(g, n)
                if (l.isEmpty()) tr(R.string.t_no_commits) else l.joinToString("\n") { "${it.id} ${it.msg}  (${it.author})" }
            }
            "branch" -> withGit { g ->
                when {
                    rest.isEmpty() -> {
                        val cur = g.repository.branch
                        ops.branches(g).joinToString("\n") { (if (it == cur) "* " else "  ") + it }
                    }
                    rest[0] == "-d" || rest[0] == "-D" -> {
                        g.branchDelete().setBranchNames(arg(rest, 1)).setForce(true).call(); tr(R.string.t_branch_deleted)
                    }
                    else -> { g.branchCreate().setName(rest[0]).call(); tr(R.string.t_branch_created, rest[0]) }
                }
            }
            "checkout", "switch" -> withGit { g ->
                val create = rest.contains("-b") || rest.contains("-c")
                val name = rest.firstOrNull { !it.startsWith("-") } ?: throw IllegalArgumentException(tr(R.string.t_need_branch))
                g.checkout().setName(name).setCreateBranch(create).call()
                tr(R.string.t_switched, g.repository.branch)
            }
            "remote" -> withGit { g ->
                val cfg = g.repository.config
                when (rest.firstOrNull()) {
                    null, "-v" -> cfg.getSubsections("remote").joinToString("\n") {
                        "$it\t${cfg.getString("remote", it, "url")}"
                    }.ifEmpty { tr(R.string.t_no_remote) }
                    "add" -> {
                        val n = arg(rest, 1); val u = arg(rest, 2)
                        cfg.setString("remote", n, "url", u)
                        cfg.setString("remote", n, "fetch", "+refs/heads/*:refs/remotes/$n/*")
                        cfg.save(); tr(R.string.t_remote_added, n)
                    }
                    "remove", "rm" -> { cfg.unsetSection("remote", arg(rest, 1)); cfg.save(); tr(R.string.t_remote_removed) }
                    else -> tr(R.string.t_remote_usage)
                }
            }
            "diff" -> withGit { g ->
                val out = ByteArrayOutputStream()
                g.diff().setOutputStream(out).call()
                out.toString("UTF-8").ifBlank { tr(R.string.t_no_diff) }.take(30_000)
            }
            "reset" -> withGit { g ->
                val hard = rest.contains("--hard")
                val ref = rest.firstOrNull { !it.startsWith("-") } ?: "HEAD"
                g.reset().setMode(if (hard) ResetCommand.ResetType.HARD else ResetCommand.ResetType.MIXED)
                    .setRef(ref).call()
                tr(R.string.t_reset_done, (if (hard) "--hard " else "") + ref)
            }
            "merge" -> withGit { g ->
                val id = g.repository.resolve(arg(rest, 0)) ?: throw IllegalArgumentException(tr(R.string.t_branch_not_found))
                "merge: ${g.merge().include(id).call().mergeStatus}"
            }
            "tag" -> withGit { g ->
                if (rest.isEmpty()) g.tagList().call().joinToString("\n") { it.name.removePrefix("refs/tags/") }
                    .ifEmpty { tr(R.string.t_no_tags) }
                else { g.tag().setName(rest[0]).call(); tr(R.string.t_tag_created, rest[0]) }
            }
            "stash" -> withGit { g ->
                if (rest.firstOrNull() == "pop") {
                    g.stashApply().call(); g.stashDrop().setStashRef(0).call(); tr(R.string.t_stash_applied)
                } else {
                    val r = g.stashCreate().call()
                    if (r == null) tr(R.string.t_stash_nothing) else tr(R.string.t_stash_saved)
                }
            }
            "rm" -> withGit { g ->
                val root = repoRoot()
                for (p in rest.filter { !it.startsWith("-") }) g.rm().addFilepattern(resolve(p).relativeTo(root).path).call()
                ""
            }
            "config" -> {
                when (rest.firstOrNull()) {
                    "user.name" -> if (rest.size > 1) { prefs.userName = rest[1]; "" } else prefs.userName
                    "user.email" -> if (rest.size > 1) { prefs.userEmail = rest[1]; "" } else prefs.userEmail
                    else -> "user.name=${prefs.userName}\nuser.email=${prefs.userEmail}"
                }
            }
            "send" -> {
                val msg = rest.firstOrNull { !it.startsWith("-") } ?: "Update from GitGo"
                val dir = repoRoot()
                kotlinx.coroutines.runBlocking { ops.send(dir, msg, ctl(emit)) }
            }
            else -> tr(R.string.t_unsupported, sub)
        }
    }

}
