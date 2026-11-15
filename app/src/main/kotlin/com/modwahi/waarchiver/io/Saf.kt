package com.modwahi.waarchiver.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import com.modwahi.waarchiver.core.SourceFile
import com.modwahi.waarchiver.core.SourceFs
import com.modwahi.waarchiver.core.UploadError
import com.modwahi.waarchiver.core.Uploader
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** حجم ملف في الوجهة بدون تنزيله (null: غير موجود). يُستخدم لمطابقة نسخة القراءة المحفوظة على التلفون. */
interface RemoteSizes {
    fun remoteSize(relDir: String, name: String): Long?
}

/** عنصر في مجلد الوجهة */
data class Entry(val name: String, val isDir: Boolean, val size: Long)

private data class DocInfo(val uri: Uri, val name: String, val size: Long, val mtimeMs: Long, val mime: String)

private fun ContentResolver.info(uri: Uri): DocInfo? = try {
    query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
        if (!c.moveToFirst()) null
        else DocInfo(uri, c.getString(0) ?: "", if (c.isNull(1)) 0 else c.getLong(1),
            if (c.isNull(2)) 0 else c.getLong(2), c.getString(3) ?: "")
    }
} catch (e: Exception) {
    null // غير موجود أو لا صلاحية
}

/**
 * مجلد واتساب عبر SAF — قراءة فقط. نبني معرّف المستند مباشرة من المسار
 * (ExternalStorageProvider: "primary:Android/media/com.whatsapp/WhatsApp/Media/...")
 * بدل البحث في الشجرة، لأن البحث بطيء جداً في مجلد وسائط كبير.
 */
class SafSource(private val context: Context, treeUri: Uri) : SourceFs {
    private val cr = context.contentResolver
    private val tree = treeUri
    private val treeDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /** المستخدم قد يختار WhatsApp أو com.whatsapp أو media: نجد جذر WhatsApp الحقيقي. */
    private val base: String by lazy {
        val candidates = listOf("", "/WhatsApp", "/com.whatsapp/WhatsApp", "/media/com.whatsapp/WhatsApp")
        candidates.map { treeDocId.trimEnd('/') + it }
            .firstOrNull { cr.info(docUri("$it/Databases")) != null }
            ?: treeDocId.trimEnd('/')
    }

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    private fun fileAt(rel: String): SourceFile? {
        val clean = rel.trimStart('/')
        val inf = cr.info(docUri("$base/$clean")) ?: return null
        if (inf.mime == Document.MIME_TYPE_DIR) return null
        return object : SourceFile {
            override val name = inf.name
            override val size = inf.size
            override val mtime = inf.mtimeMs / 1000.0
            override fun open(): InputStream =
                cr.openInputStream(inf.uri) ?: throw FileNotFoundException(rel) // "r": قراءة فقط
        }
    }

    override val backupDescription: String
        get() = "${base.substringAfter(':')}/Databases/msgstore.db.crypt15"

    override fun backup(): SourceFile? = fileAt("Databases/msgstore.db.crypt15")

    override fun media(rel: String): SourceFile? {
        // المسار في القاعدة قد يكون مطلقاً في نسخ قديمة: /storage/emulated/0/.../WhatsApp/Media/...
        val r = if (rel.startsWith("/")) {
            val i = rel.indexOf("/WhatsApp/")
            if (i < 0) return null
            rel.substring(i + "/WhatsApp/".length)
        } else rel
        return fileAt(r)
    }

    /** هل يبدو المجلد المختار مجلد واتساب؟ */
    fun looksValid(): Boolean = cr.info(docUri("$base/Databases")) != null || cr.info(docUri("$base/Media")) != null
}

/**
 * «حفظ محلي فقط»: نفس شجرة الدرايف لكن في مجلد يختاره المستخدم على التلفون (SAF).
 * لا حذف أبداً: نُنشئ الملفات أو نكتب فوق الرسائل.json/txt فقط.
 */
class SafUploader(private val context: Context, private val tree: Uri) : Uploader, RemoteSizes {
    private val cr = context.contentResolver
    private val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private val childCache = HashMap<Uri, MutableMap<String, DocInfo>>()

    private fun children(dir: Uri): MutableMap<String, DocInfo> = childCache.getOrPut(dir) {
        val out = HashMap<String, DocInfo>()
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir))
        cr.query(q, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out[name] = DocInfo(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name,
                    if (c.isNull(2)) 0 else c.getLong(2), if (c.isNull(3)) 0 else c.getLong(3), c.getString(4) ?: "")
            }
        } ?: throw UploadError("لا أستطيع قراءة مجلد الحفظ المحلي (ربما سُحبت الصلاحية). اختره مرة أخرى من الإعدادات.")
        out
    }

    private fun findDir(relDir: String, create: Boolean): Uri? {
        var cur = rootDoc
        for (part in relDir.split('/').filter { it.isNotEmpty() }) {
            val kids = children(cur)
            val found = kids[part]
            cur = when {
                found != null && found.mime == Document.MIME_TYPE_DIR -> found.uri
                found != null -> throw UploadError("يوجد ملف باسم المجلد «$part» في مجلد الحفظ")
                !create -> return null
                else -> {
                    val u = DocumentsContract.createDocument(cr, cur, Document.MIME_TYPE_DIR, part)
                        ?: throw UploadError("تعذّر إنشاء المجلد «$part»")
                    kids[part] = DocInfo(u, part, 0, 0, Document.MIME_TYPE_DIR)
                    u
                }
            }
        }
        return cur
    }

    /** محتوى مجلد في الوجهة (لمزامنة نسخة القراءة) */
    fun list(relDir: String): List<Entry> {
        val dir = findDir(relDir, create = false) ?: return emptyList()
        return children(dir).values.map { Entry(it.name, it.mime == Document.MIME_TYPE_DIR, it.size) }
    }

    override fun remoteSize(relDir: String, name: String): Long? {
        val dir = findDir(relDir, create = false) ?: return null
        return children(dir)[name]?.size
    }

    override fun readFile(relDir: String, name: String): ByteArray? {
        val dir = findDir(relDir, create = false) ?: return null
        val f = children(dir)[name] ?: return null
        return cr.openInputStream(f.uri)?.use { it.readBytes() }
    }

    override fun uploadDir(localDir: File, relDir: String) = uploadDir(localDir, relDir) { }

    override fun uploadDir(localDir: File, relDir: String, onBytes: (Long) -> Unit) {
        localDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { src ->
            val relParent = src.parentFile!!.relativeTo(localDir).path
            val dir = findDir(listOf(relDir, relParent).filter { it.isNotEmpty() }.joinToString("/"), create = true)!!
            val kids = children(dir)
            val existing = kids[src.name]
            if (existing != null && !src.name.startsWith("الرسائل.") && !src.name.endsWith(".txt") &&
                existing.size == src.length()) { onBytes(src.length()); return@forEach } // وسيط موجود بنفس الحجم
            val target = existing?.uri ?: run {
                val ext = src.extension.lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                (DocumentsContract.createDocument(cr, dir, mime, src.name)
                    ?: throw UploadError("تعذّر إنشاء ${src.name}")).also {
                    kids[src.name] = DocInfo(it, src.name, 0, 0, mime)
                }
            }
            (cr.openOutputStream(target, "wt") ?: throw UploadError("تعذّر الكتابة في ${src.name}")).use { out ->
                src.inputStream().use { inp ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        }
    }
}
