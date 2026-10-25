package com.gitgo.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File

enum class DK { CTX, ADD, DEL, SEP }

/** Строка diff. oldNo/newNo = 0, если строки нет в старой/новой версии. */
data class DLine(val kind: DK, val text: String, val oldNo: Int, val newNo: Int)

object DiffEngine {
    const val MAX_BYTES = 1_500_000

    /** Содержимое файла в HEAD или null (новый файл / нет коммитов / слишком большой / бинарный). */
    fun headBytes(git: Git, path: String): ByteArray? = try {
        val repo = git.repository
        val tree = repo.resolve("HEAD^{tree}")
        if (tree == null) null
        else TreeWalk.forPath(repo, path, tree)?.use { tw ->
            repo.open(tw.getObjectId(0)).getCachedBytes(MAX_BYTES)
        }
    } catch (e: Exception) {
        null
    }

    /** true, если байты — корректный UTF-8 (безопасно редактировать и сохранять обратно). */
    fun isUtf8(b: ByteArray): Boolean = b.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8).contentEquals(b)

    fun isBinary(b: ByteArray): Boolean {
        val n = minOf(b.size, 8000)
        for (i in 0 until n) if (b[i].toInt() == 0) return true
        return false
    }

    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split("\n").map { it.removeSuffix("\r") }
        return if (text.endsWith("\n")) parts.dropLast(1) else parts
    }

    /** Построчный diff (общий префикс/суффикс + LCS для середины) с контекстом вокруг изменений. */
    fun compute(old: List<String>, new: List<String>, context: Int = 3): List<DLine> {
        var p = 0
        while (p < old.size && p < new.size && old[p] == new[p]) p++
        var s = 0
        while (s < old.size - p && s < new.size - p && old[old.size - 1 - s] == new[new.size - 1 - s]) s++

        val full = ArrayList<DLine>()
        var o = 1
        var n = 1
        for (i in 0 until p) full += DLine(DK.CTX, old[i], o++, n++)

        val a = old.subList(p, old.size - s)
        val b = new.subList(p, new.size - s)
        val na = a.size
        val nb = b.size
        if (na.toLong() * nb <= 4_000_000L) {
            val w = nb + 1
            val dp = IntArray((na + 1) * w)
            for (i in na - 1 downTo 0) for (j in nb - 1 downTo 0) {
                dp[i * w + j] = if (a[i] == b[j]) dp[(i + 1) * w + j + 1] + 1
                else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
            }
            var i = 0
            var j = 0
            while (i < na && j < nb) {
                if (a[i] == b[j]) { full += DLine(DK.CTX, a[i], o++, n++); i++; j++ }
                else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) { full += DLine(DK.DEL, a[i], o++, 0); i++ }
                else { full += DLine(DK.ADD, b[j], 0, n++); j++ }
            }
            while (i < na) { full += DLine(DK.DEL, a[i], o++, 0); i++ }
            while (j < nb) { full += DLine(DK.ADD, b[j], 0, n++); j++ }
        } else {
            for (x in a) full += DLine(DK.DEL, x, o++, 0)
            for (x in b) full += DLine(DK.ADD, x, 0, n++)
        }
        for (i in (old.size - s) until old.size) full += DLine(DK.CTX, old[i], o++, n++)

        val show = BooleanArray(full.size)
        var any = false
        for (i in full.indices) if (full[i].kind != DK.CTX) {
            any = true
            for (k in maxOf(0, i - context)..minOf(full.size - 1, i + context)) show[k] = true
        }
        if (!any) return emptyList()
        val out = ArrayList<DLine>()
        var hidden = 0
        for (i in full.indices) {
            if (show[i]) {
                if (hidden > 0) { out += DLine(DK.SEP, hidden.toString(), 0, 0); hidden = 0 }
                out += full[i]
            } else hidden++
        }
        if (hidden > 0) out += DLine(DK.SEP, hidden.toString(), 0, 0)
        return out
    }

    /** Заменяет строку №newNo (с 1) в рабочем файле, сохраняя перевод строки CRLF. */
    fun editLine(file: File, newNo: Int, newText: String) {
        val bytes = file.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        // Файл не в UTF-8 (или смешанные кодировки): перезапись испортила бы его — отказываемся.
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) throw IllegalStateException(tr(R.string.editor_cannot))
        val parts = text.split("\n").toMutableList()
        val idx = newNo - 1
        if (idx !in parts.indices) return
        val cr = parts[idx].endsWith("\r")
        parts[idx] = newText + if (cr) "\r" else ""
        file.writeBytes(parts.joinToString("\n").toByteArray(Charsets.UTF_8))
    }
}

// ───────────────────────── Подсветка синтаксиса ─────────────────────────

class SynColors(val keyword: Color, val string: Color, val comment: Color, val number: Color)

object Syntax {
    private class Lang(val comment: String?, val keywords: Set<String>, val quotes: Boolean = true) {
        val regex: Regex by lazy {
            val c = if (comment != null) "(${Regex.escape(comment)}.*)" else "((?!))"
            val str = if (quotes) "(\"(?:\\\\.|[^\"\\\\])*\"?|'(?:\\\\.|[^'\\\\])*'?)" else "((?!))"
            Regex("$c|$str|(\\b\\d[\\w.]*)|(\\b[A-Za-z_][A-Za-z0-9_]*\\b)")
        }
    }

    private fun kw(s: String) = s.split(" ").toSet()
    private val kotlin = Lang("//", kw("fun val var class object interface if else when for while return import package private public protected internal override suspend data sealed enum in is as null true false this super try catch finally throw companion open abstract const lateinit by init typealias"))
    private val java = Lang("//", kw("class interface enum extends implements import package public private protected static final void int long double float boolean char byte short new return if else for while switch case break continue try catch finally throw throws null true false this super abstract synchronized volatile"))
    private val js = Lang("//", kw("function const let var class extends import export from default return if else for while switch case break continue try catch finally throw new null undefined true false this async await typeof instanceof of in interface type enum implements"))
    private val c = Lang("//", kw("int long short char float double void unsigned signed struct union enum typedef static const extern if else for while do switch case break continue return sizeof goto class public private protected namespace using template new delete nullptr true false auto include define"))
    private val go = Lang("//", kw("func package import var const type struct interface map chan go defer return if else for range switch case default break continue select nil true false"))
    private val rust = Lang("//", kw("fn let mut const static struct enum impl trait pub use mod crate self super if else match for while loop return break continue as in ref move async await dyn where true false"))
    private val py = Lang("#", kw("def class import from as if elif else for while return try except finally raise with lambda pass break continue None True False and or not in is global nonlocal yield async await"))
    private val sh = Lang("#", kw("if then else elif fi for while do done case esac function in echo export local return exit cd set unset source"))
    private val yaml = Lang("#", kw("true false null yes no on"))
    private val json = Lang(null, kw("true false null"))
    private val sql = Lang("--", kw("select from where insert into values update set delete create table alter drop join left right inner on group by order limit and or not null as primary key"))
    private val xml = Lang(null, kw(""))

    private val byExt: Map<String, Lang> = mapOf(
        "kt" to kotlin, "kts" to kotlin, "gradle" to kotlin,
        "java" to java, "cs" to java, "dart" to java, "swift" to java, "scala" to java,
        "js" to js, "jsx" to js, "ts" to js, "tsx" to js, "mjs" to js, "php" to js,
        "c" to c, "h" to c, "cpp" to c, "hpp" to c, "cc" to c, "m" to c,
        "go" to go, "rs" to rust,
        "py" to py, "rb" to py, "toml" to yaml, "yml" to yaml, "yaml" to yaml, "properties" to yaml, "conf" to yaml, "ini" to yaml,
        "sh" to sh, "bash" to sh, "zsh" to sh,
        "json" to json, "sql" to sql, "xml" to xml, "html" to xml, "svg" to xml
    )

    fun supported(ext: String) = byExt.containsKey(ext.lowercase())

    fun highlight(line: String, ext: String, col: SynColors): AnnotatedString {
        val lang = byExt[ext.lowercase()] ?: return AnnotatedString(line)
        return buildAnnotatedString {
            append(line)
            for (m in lang.regex.findAll(line)) {
                val g = m.groups
                val color = when {
                    g[1] != null -> col.comment
                    g[2] != null -> col.string
                    g[3] != null -> col.number
                    g[4] != null && g[4]!!.value in lang.keywords -> col.keyword
                    else -> null
                }
                if (color != null) addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
            }
        }
    }

    /** Подсветка целого текста для редактора (длина текста не меняется). */
    fun highlightAll(text: String, ext: String, col: SynColors): AnnotatedString {
        if (!supported(ext) || text.length > 120_000) return AnnotatedString(text)
        return buildAnnotatedString {
            var off = 0
            for ((i, ln) in text.split("\n").withIndex()) {
                if (i > 0) { append("\n"); off += 1 }
                val h = highlight(ln, ext, col)
                append(h)
                off += ln.length
            }
        }
    }
}
