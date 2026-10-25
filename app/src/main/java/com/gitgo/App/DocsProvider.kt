package com.gitgo.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

const val DOCS_AUTHORITY = "com.gitgo.app.documents"
private const val ROOT_ID = "gitgo"

fun reposRoot(ctx: Context): File = File(ctx.filesDir, "repos").also { it.mkdirs() }

private fun docId(base: File, f: File): String {
    val b = base.canonicalPath
    val p = f.canonicalPath
    return if (p == b) ROOT_ID else "$ROOT_ID/" + p.removePrefix("$b/")
}

private fun mimeOf(f: File): String {
    if (f.isDirectory) return Document.MIME_TYPE_DIR
    val ext = f.extension.lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: if (ext in textExts) "text/plain" else "application/octet-stream"
}

private val textExts = setOf("kt", "kts", "java", "gradle", "yml", "yaml", "toml", "md", "txt", "sh", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "go", "rs", "json", "xml", "properties", "ini", "conf", "gitignore", "csv", "sql", "rb", "php", "swift", "dart")

/**
 * Показывает репозитории GitGo как хранилище в системном выборе файлов: редакторы (Acode и др.)
 * могут открыть папку и править файлы. Доступ выдаёт пользователь через системный диалог.
 */
class GitGoDocsProvider : DocumentsProvider() {

    private fun base(): File = reposRoot(context!!)

    private fun fileFor(id: String): File {
        val b = base().canonicalFile
        val f = if (id == ROOT_ID) b else File(b, id.removePrefix("$ROOT_ID/")).canonicalFile
        if (f.path != b.path && !f.path.startsWith(b.path + "/")) throw FileNotFoundException("outside root")
        return f
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_SUMMARY))
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(Root.COLUMN_TITLE, "GitGo")
            add(Root.COLUMN_SUMMARY, tr(R.string.docs_root_summary))
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        return c
    }

    private val docProjection = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
    )

    private fun include(c: MatrixCursor, f: File) {
        val isRoot = f.canonicalPath == base().canonicalPath
        var flags = 0
        if (f.isDirectory) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        else flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (!isRoot) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId(base(), f))
            add(Document.COLUMN_DISPLAY_NAME, if (isRoot) "GitGo" else f.name)
            add(Document.COLUMN_SIZE, f.length())
            add(Document.COLUMN_MIME_TYPE, mimeOf(f))
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: docProjection)
        include(c, fileFor(documentId))
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: docProjection)
        val root = base()
        // ссылки, ведущие за пределы хранилища, наружу не показываем
        fileFor(parentDocumentId).listFiles()?.filter { it.isInside(root) }?.sortedBy { it.name.lowercase() }?.forEach { include(c, it) }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        val clean = displayName.replace('/', '_').ifBlank { "file" }
        var f = File(parent, clean)
        var i = 1
        while (f.exists()) { f = File(parent, "$clean ($i)"); i++ }
        if (mimeType == Document.MIME_TYPE_DIR) f.mkdirs() else { f.parentFile?.mkdirs(); f.createNewFile() }
        return docId(base(), f)
    }

    override fun deleteDocument(documentId: String) {
        val f = fileFor(documentId)
        if (f.canonicalPath == base().canonicalPath) throw FileNotFoundException()
        f.deleteTree()
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val f = fileFor(documentId)
        val target = File(f.parentFile, displayName.replace('/', '_'))
        if (!f.renameTo(target)) throw FileNotFoundException()
        return docId(base(), target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val p = fileFor(parentDocumentId).canonicalPath
        val c = fileFor(documentId).canonicalPath
        c == p || c.startsWith("$p/")
    } catch (e: Exception) { false }

    override fun getDocumentType(documentId: String): String = mimeOf(fileFor(documentId))
}

/** «Открыть в…»: передача папки или файла другим приложениям. */
object OpenIn {
    private fun uriFor(ctx: Context, f: File): Uri =
        DocumentsContract.buildDocumentUri(DOCS_AUTHORITY, docId(reposRoot(ctx), f))

    fun folder(ctx: Context, dir: File) {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(ctx, dir), Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            ctx.startActivity(Intent.createChooser(i, tr(R.string.menu_open_in)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, tr(R.string.open_in_none), Toast.LENGTH_LONG).show()
        }
    }

    fun file(ctx: Context, f: File) {
        val i = Intent(Intent.ACTION_EDIT)
            .setDataAndType(uriFor(ctx, f), mimeOf(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            ctx.startActivity(Intent.createChooser(i, tr(R.string.open_with)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, tr(R.string.open_in_none), Toast.LENGTH_LONG).show()
        }
    }

    /** Запускает Acode; папку в нём выбирают через системный выбор файлов (хранилище GitGo). */
    fun acode(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage("com.foxdebug.acode")
        if (i == null) Toast.makeText(ctx, tr(R.string.acode_missing), Toast.LENGTH_LONG).show()
        else {
            Toast.makeText(ctx, tr(R.string.acode_hint), Toast.LENGTH_LONG).show()
            ctx.startActivity(i)
        }
    }
}

/** Копирует рабочую папку репозитория (без .git) в папку, выбранную пользователем (SAF). */
class Exporter(private val ctx: Context) {
    suspend fun export(tree: Uri, src: File, ctl: Ctl): String {
        val files = src.walkTopDown().filter { it.isFile && !it.relativeTo(src).path.startsWith(".git" + File.separator) && it.relativeTo(src).path != ".git" }.toList()
        val total = files.sumOf { it.length() }
        val meter = SpeedMeter()
        var done = 0L
        val resolver = ctx.contentResolver
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val top = DocumentsContract.createDocument(resolver, rootUri, Document.MIME_TYPE_DIR, src.name)
            ?: throw IllegalStateException("createDocument failed")
        val dirs = HashMap<String, Uri>()
        dirs[""] = top
        fun dirUri(rel: String): Uri {
            dirs[rel]?.let { return it }
            val parentRel = rel.substringBeforeLast('/', "")
            val parent = dirUri(parentRel)
            val u = DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, rel.substringAfterLast('/'))
                ?: throw IllegalStateException("createDocument failed")
            dirs[rel] = u
            return u
        }
        val buf = ByteArray(256 * 1024)
        var count = 0
        for (f in files) {
            currentCoroutineContext().ensureActive()
            val rel = f.relativeTo(src).path.replace(File.separatorChar, '/')
            val parent = dirUri(rel.substringBeforeLast('/', ""))
            val ext = f.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val out = DocumentsContract.createDocument(resolver, parent, mime, f.name) ?: continue
            resolver.openOutputStream(out, "w")?.use { os ->
                f.inputStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        ctl.report(meter.make(tr(R.string.job_exporting, src.name), done, total, "B"))
                        if (ctl.cancelled()) throw CancellationException(tr(R.string.cancelled))
                    }
                }
            }
            count++
        }
        return tr(R.string.export_done, count, fmtBytes(done))
    }
}
