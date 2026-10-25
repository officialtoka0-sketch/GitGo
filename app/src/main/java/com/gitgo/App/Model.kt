package com.gitgo.app

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale

data class Progress(
    val title: String,
    val done: Long = 0,
    val total: Long = 0,
    val speed: Double = 0.0,
    val etaSec: Long = -1,
    val unit: String = "B",
    val state: State = State.RUNNING,
    val message: String = ""
) {
    enum class State { RUNNING, DONE, ERROR, CANCELLED }

    val percent: Int get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1

    fun line(): String {
        if (state != State.RUNNING) return message
        val parts = ArrayList<String>()
        if (percent >= 0) parts += "$percent%"
        if (unit == "B") {
            parts += if (total > 0) "${fmtBytes(done)} / ${fmtBytes(total)}" else fmtBytes(done)
            if (speed > 0) parts += tr(R.string.per_sec, fmtBytes(speed.toLong()))
        } else {
            if (total > 0) parts += "$done / $total"
            if (speed > 0) parts += tr(R.string.obj_per_sec, speed.toInt())
        }
        if (etaSec >= 0) parts += tr(R.string.eta_left, fmtEta(etaSec))
        return parts.joinToString(" • ")
    }
}

fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b ${tr(R.string.unit_b)}"
    val units = arrayOf(tr(R.string.unit_kb), tr(R.string.unit_mb), tr(R.string.unit_gb), tr(R.string.unit_tb))
    var v = b.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

fun fmtEta(s: Long): String = when {
    s < 60 -> tr(R.string.t_sec, s.toInt())
    s < 3600 -> tr(R.string.t_min_sec, (s / 60).toInt(), (s % 60).toInt())
    else -> tr(R.string.t_h_min, (s / 3600).toInt(), ((s % 3600) / 60).toInt())
}

class SpeedMeter {
    private var t0 = System.nanoTime()
    fun reset() { t0 = System.nanoTime() }
    fun elapsedSec(): Double = (System.nanoTime() - t0) / 1e9
    fun make(title: String, done: Long, total: Long, unit: String): Progress {
        val sec = elapsedSec()
        val sp = if (sec > 0.5) done / sec else 0.0
        val eta = if (sp > 0 && total > done) ((total - done) / sp).toLong() else -1L
        return Progress(title, done, total, sp, eta, unit)
    }
}

/** Управление задачей: отчёт о прогрессе и проверка отмены. */
class Ctl(val report: (Progress) -> Unit, val cancelled: () -> Boolean)

object TransferState {
    val current = MutableStateFlow<Progress?>(null)
}

/**
 * Удаляет файл или дерево, не заходя по символьным ссылкам: репозиторий из сети может содержать ссылку на
 * чужую папку, и обычный deleteRecursively() стёр бы её содержимое.
 */
fun File.deleteTree(): Boolean {
    if (!java.nio.file.Files.isSymbolicLink(toPath()) && isDirectory) listFiles()?.forEach { it.deleteTree() }
    return delete()
}

/** true, если реальный путь файла (со всеми ссылками) лежит внутри папки root. */
fun File.isInside(root: File): Boolean = try {
    canonicalPath.startsWith(root.canonicalPath + File.separator)
} catch (e: Exception) {
    false
}
