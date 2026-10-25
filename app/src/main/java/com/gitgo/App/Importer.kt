package com.gitgo.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream

/** Копирует выбранные пользователем файлы/папки (Storage Access Framework) внутрь рабочей папки репозитория. */
class Importer(private val ctx: Context) {

    private class Item(val uri: Uri, val rel: String, val size: Long)

    private fun safe(name: String): String {
        val n = name.replace('/', '_').replace('\\', '_').trim()
        return if (n.isEmpty() || n == "." || n == ".." || n == ".git") "_" + n.trim('.') else n
    }

    private fun nameSize(u: Uri): Pair<String, Long> {
        var name = "file"
        var size = 0L
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        return name to size
    }

    suspend fun importFiles(uris: List<Uri>, target: File, ctl: Ctl): String {
        val items = uris.map { u -> val (n, s) = nameSize(u); Item(u, safe(n), s) }
        return copyAll(items, target, ctl)
    }

    suspend fun importTree(tree: Uri, target: File, ctl: Ctl): String {
        ctl.report(Progress(tr(R.string.imp_reading)))
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = DocumentFile.fromTreeUri(ctx, tree)?.name ?: "folder"
        val items = ArrayList<Item>()
        walk(tree, rootId, safe(rootName), items)
        return copyAll(items, target, ctl)
    }

    private suspend fun walk(tree: Uri, docId: String, rel: String, out: MutableList<Item>) {
        currentCoroutineContext().ensureActive()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE
        )
        val dirs = ArrayList<Pair<String, String>>()
        ctx.contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val rawName = c.getString(1) ?: "_"
                val name = safe(rawName)
                val isDir = c.getString(2) == Document.MIME_TYPE_DIR
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                if (isDir) {
                    if (rawName != ".git") dirs += id to name
                } else {
                    out += Item(DocumentsContract.buildDocumentUriUsingTree(tree, id), "$rel/$name", size)
                }
            }
        }
        for ((id, name) in dirs) walk(tree, id, "$rel/$name", out)
    }

    private suspend fun copyAll(items: List<Item>, target: File, ctl: Ctl): String {
        target.mkdirs()
        val base = target.canonicalFile
        val total = items.sumOf { it.size }
        val meter = SpeedMeter()
        var done = 0L
        var lastRep = 0L
        var count = 0
        val buf = ByteArray(256 * 1024)
        for ((idx, item) in items.withIndex()) {
            currentCoroutineContext().ensureActive()
            val out = File(base, item.rel).canonicalFile
            if (!out.path.startsWith(base.path + File.separator)) continue // защита от выхода за пределы папки
            if (out.relativeTo(base).path.split(File.separator).any { it == ".git" }) continue // никогда не трогаем .git
            out.parentFile?.mkdirs()
            val input = ctx.contentResolver.openInputStream(item.uri) ?: continue
            input.use { ins ->
                FileOutputStream(out).use { os ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastRep > 250) {
                            lastRep = now
                            ctl.report(meter.make(tr(R.string.imp_copying, idx + 1, items.size), done, total, "B"))
                            if (ctl.cancelled()) throw CancellationException(tr(R.string.cancelled))
                        }
                    }
                }
            }
            count++
        }
        return tr(R.string.imp_done, count, fmtBytes(done))
    }
}
