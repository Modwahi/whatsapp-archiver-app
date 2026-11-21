package com.modwahi.waarchiver.core

import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ملف للقراءة فقط من مجلد واتساب. */
interface SourceFile {
    val name: String
    val size: Long
    /** وقت آخر تعديل بالثواني */
    val mtime: Double
    fun open(): InputStream
}

/** مجلد واتساب (قراءة فقط): File في الاختبارات، و SAF في التطبيق. */
interface SourceFs {
    /** وصف مكان ملف النسخة لرسائل الخطأ */
    val backupDescription: String
    /**
     * أحدث نسخة احتياطية في Databases (حسب وقت التعديل) أو null إن لم توجد.
     * واتساب قد يكتب النسخة اليومية التلقائية باسم مؤرّخ (msgstore-2026-11-21.1.db.crypt15)
     * ويترك msgstore.db.crypt15 كما هو، فلا نكتفي بالاسم الثابت.
     */
    fun backup(): SourceFile?

    /** ملفات النسخ التزايدية msgstore-increment-N.db.crypt15 (للتشخيص). */
    fun increments(): List<SourceFile> = emptyList()

    companion object {
        /** msgstore.db.crypt15 أو msgstore-YYYY-MM-DD.N.db.crypt15 */
        val INCREMENT_NAME = Regex("""msgstore-increment-\d+(-[\d-]+)?(\.\d+)?\.db\.crypt15""")
        val BACKUP_NAME = Regex("""msgstore(-\d{4}-\d{2}-\d{2}\.\d+)?\.db\.crypt15""")
        fun newest(files: Sequence<SourceFile>): SourceFile? =
            files.filter { BACKUP_NAME.matches(it.name) && it.size > 0 }
                // عند التساوي نفضّل الاسم الثابت
                .maxWithOrNull(compareBy<SourceFile> { it.mtime }.thenBy { it.name == "msgstore.db.crypt15" })
    }
    /** ملف وسيط بمساره المسجّل في القاعدة (نسبي لمجلد WhatsApp، أو مطلق). null إن لم يوجد كملف. */
    fun media(rel: String): SourceFile?
}

/**
 * الرفع: قراءة ملف موجود (لندمج معه دون تكرار) ونسخ مجلد — بدون حذف أبداً.
 * readFile يرمي خطأ عند مشكلة اتصال (حتى لا نكتب فوق ملف موجود)، ويرجع null فقط إن لم يوجد الملف.
 */
interface Uploader {
    fun readFile(relDir: String, name: String): ByteArray?
    fun uploadDir(localDir: File, relDir: String)

    /** نفس uploadDir مع الإبلاغ عن البايتات المرفوعة (فرق موجب، أو سالب عند إعادة محاولة ملف). */
    fun uploadDir(localDir: File, relDir: String, onBytes: (Long) -> Unit) {
        uploadDir(localDir, relDir)
        onBytes(localDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }
}

class UploadError(message: String, cause: Throwable? = null) : Exception(message, cause)

class FileSourceFs(private val root: File) : SourceFs {
    override val backupDescription: String get() = File(root, "Databases/msgstore*.db.crypt15").path

    private fun wrap(f: File): SourceFile? = if (!f.isFile) null else object : SourceFile {
        override val name = f.name
        override val size = f.length()
        override val mtime = f.lastModified() / 1000.0
        override fun open(): InputStream = f.inputStream()
    }

    override fun backup(): SourceFile? =
        SourceFs.newest((File(root, "Databases").listFiles() ?: emptyArray()).asSequence().mapNotNull { wrap(it) })
    override fun increments(): List<SourceFile> =
        (File(root, "Databases").listFiles() ?: emptyArray()).filter { SourceFs.INCREMENT_NAME.matches(it.name) }
            .mapNotNull { wrap(it) }.sortedBy { it.mtime }

    override fun media(rel: String): SourceFile? = wrap(if (rel.startsWith("/")) File(rel) else File(root, rel))
}

/** نفس واجهة الرفع لكن على مجلد محلي (وضع «حفظ محلي فقط» في الاختبارات). */
class LocalUploader(private val base: File) : Uploader {
    override fun readFile(relDir: String, name: String): ByteArray? {
        val p = File(File(base, relDir), name)
        return if (p.exists()) p.readBytes() else null
    }

    override fun uploadDir(localDir: File, relDir: String) = uploadDir(localDir, relDir) { }

    override fun uploadDir(localDir: File, relDir: String, onBytes: (Long) -> Unit) {
        val dest = File(base, relDir)
        localDir.walkTopDown().filter { it.isFile }.forEach { src ->
            val target = File(dest, src.relativeTo(localDir).path)
            target.parentFile?.mkdirs()
            src.copyTo(target, overwrite = true)
            target.setLastModified(src.lastModified())
            onBytes(src.length())
        }
    }
}

/** سجل التشغيل — مع حجب أي نص يشبه المفتاح. */
interface ArchLog {
    fun info(msg: String)
    fun warning(msg: String)
    fun error(msg: String)

    companion object {
        private val HEX64 = Regex("(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")
        fun redact(text: String): String = HEX64.replace(text, "<مفتاح-محجوب>")
    }
}

/** يكتب في logs/<التاريخ>.log مثل الأداة: [YYYY-MM-DD HH:MM:SS] LEVEL   message */
class FileLog(private val dir: File, private val echo: ((String) -> Unit)? = null) : ArchLog {
    private fun write(level: String, msg: String) {
        val now = Date()
        val line = "[" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(now) + "] " +
            level.padEnd(7) + " " + ArchLog.redact(msg)
        synchronized(this) {
            dir.mkdirs()
            File(dir, SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now) + ".log")
                .appendText(line + "\n", Charsets.UTF_8)
        }
        echo?.invoke(line)
    }

    override fun info(msg: String) = write("INFO", msg)
    override fun warning(msg: String) = write("WARNING", msg)
    override fun error(msg: String) = write("ERROR", msg)
}
