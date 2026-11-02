package com.modwahi.waarchiver.work

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.modwahi.waarchiver.core.ArchLog
import com.modwahi.waarchiver.core.ChatInfo
import com.modwahi.waarchiver.core.Crypt15
import com.modwahi.waarchiver.core.FileLog
import com.modwahi.waarchiver.core.Paths
import com.modwahi.waarchiver.core.RunError
import com.modwahi.waarchiver.core.RunLock
import com.modwahi.waarchiver.core.Summary
import com.modwahi.waarchiver.core.UploadError
import com.modwahi.waarchiver.core.Uploader
import com.modwahi.waarchiver.core.runArchive
import com.modwahi.waarchiver.core.withDecryptedDb
import com.modwahi.waarchiver.data.DRIVE_FOLDER_NAME
import com.modwahi.waarchiver.data.KeyVault
import com.modwahi.waarchiver.data.KnownChat
import com.modwahi.waarchiver.data.Mode
import com.modwahi.waarchiver.data.Prefs
import com.modwahi.waarchiver.io.AndroidSql
import com.modwahi.waarchiver.io.DriveClient
import com.modwahi.waarchiver.io.DriveUploader
import com.modwahi.waarchiver.io.GoogleAuth
import com.modwahi.waarchiver.io.SafSource
import com.modwahi.waarchiver.io.SafUploader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** يجمع أجزاء التشغيل: الإعدادات، المفتاح، مصدر SAF، الرافع (Drive أو محلي)، والسجلات. */
class Engine(private val context: Context) {
    val prefs = Prefs(context)
    val vault = KeyVault(context)
    val paths = Paths(home = File(context.filesDir, "archiver"), tmp = File(context.cacheDir, "work"))
    val log: ArchLog = FileLog(paths.logs)

    fun source(): SafSource {
        val uri = prefs.waTreeUri ?: throw RunError("لم تختر مجلد واتساب بعد. افتح التطبيق وأكمل الإعداد.")
        return SafSource(context, Uri.parse(uri))
    }

    private fun key(): String = vault.load() ?: throw RunError("لا يوجد مفتاح محفوظ (أو تعذّر فكّه). أدخل المفتاح من الإعدادات.")

    fun driveClient(): DriveClient {
        val account = prefs.driveAccount ?: throw UploadError("لم تسجّل الدخول إلى Google بعد (الإعدادات ← الحساب).")
        return DriveClient { force -> GoogleAuth.tokenBlocking(context, account, force) }
    }

    /** مجلد التطبيق في الدرايف بالـ ID المحفوظ؛ يُنشأ في الجذر أول مرة فقط. */
    fun ensureDriveFolder(drive: DriveClient): String {
        prefs.driveFolderId?.let { id ->
            val f = drive.get(id)
            if (f != null && f.mime == DriveClient.FOLDER_MIME) {
                if (f.trashed) throw UploadError("مجلد «${f.name}» في سلة مهملات Google Drive. استرجعه ثم أعد التشغيل.")
                return id
            }
            log.warning("مجلد الدرايف المحفوظ لم يعد موجوداً؛ سأنشئ مجلداً جديداً")
        }
        val id = drive.createFolder(DRIVE_FOLDER_NAME, null)
        prefs.driveFolderId = id
        log.info("أنشأت مجلد «$DRIVE_FOLDER_NAME» في Google Drive")
        return id
    }

    private fun uploader(): Uploader = when (prefs.mode) {
        Mode.LOCAL -> SafUploader(context, Uri.parse(prefs.localTreeUri
            ?: throw RunError("لم تختر مجلد الحفظ المحلي (الإعدادات ← وجهة الحفظ).")))
        Mode.DRIVE -> object : Uploader {
            // يُجهَّز عند أول استخدام، حتى يظهر خطأ الاتصال في ملخص المحادثة كما في الأداة
            private val inner by lazy { val d = driveClient(); DriveUploader(d, ensureDriveFolder(d)) }
            override fun readFile(relDir: String, name: String) = inner.readFile(relDir, name)
            override fun uploadDir(localDir: File, relDir: String) = inner.uploadDir(localDir, relDir)
        }
    }

    /** فك تجريبي سريع لأول جزء من النسخة. */
    fun verifyKey(key: String) {
        val b = source().backup() ?: throw RunError("لم أجد ملف النسخة الاحتياطية Databases/msgstore.db.crypt15 في المجلد المختار.")
        b.open().use { Crypt15.verifyKey(it, key) }
    }

    fun backupAgeHours(): Double? = runCatching {
        source().backup()?.mtime?.let { (System.currentTimeMillis() / 1000.0 - it) / 3600 }
    }.getOrNull()

    /** يفك النسخة ويقرأ قائمة المحادثات (لشاشة المحادثات)، ثم يحذف القاعدة المفكوكة. */
    fun listChats(progress: (String, Double?) -> Unit): List<ChatInfo> = RunLock.with(paths.lock) {
        withDecryptedDb(source(), paths, key(), log, AndroidSql, { progress("فك النسخة الاحتياطية", it) }) { db ->
            progress("قراءة المحادثات", null)
            db.listChats()
        }
    }.also { chats ->
        prefs.knownChats = chats.map { KnownChat(it.jid, it.subject, it.isGroup, it.messageCount, it.lastTimestamp) }
        prefs.knownChatsAt = System.currentTimeMillis()
    }

    /** التشغيل الحقيقي أو التجربة. يرجع null إن تم التخطي (لا نسخة جديدة في الوضع المجدول). */
    fun run(scheduled: Boolean, dryRun: Boolean, progress: (String, Double?) -> Unit): Pair<Summary?, String?> {
        val cfg = prefs.archiveConfig()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(Date())
        val dryDir = File(context.cacheDir, "dry/$stamp")
        val up: Uploader = if (dryRun) {
            // التجربة لا تقرأ ولا تكتب في الوجهة (مثل الأداة)
            object : Uploader {
                override fun readFile(relDir: String, name: String): ByteArray? = null
                override fun uploadDir(localDir: File, relDir: String) {}
            }
        } else uploader()
        val summary = runArchive(cfg, paths, key(), source(), up, log, AndroidSql, prefs.contacts,
            dryRun = dryRun, scheduled = scheduled, dryDir = if (dryRun) dryDir else null, onProgress = progress)
        var exported: String? = null
        if (dryRun && summary != null) {
            File(dryDir, "_ملخص التجربة.txt").writeText(summary.render())
            exported = exportToDownloads(dryDir, "أرشيف واتساب - تجربة/$stamp")
            dryDir.deleteRecursively()
        }
        if (summary != null) saveLastRun(summary, exported)
        return summary to exported
    }

    private fun saveLastRun(s: Summary, exported: String?) {
        val o = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("ok", s.ok)
            .put("dry", s.dryRun)
            .put("text", s.render())
            .put("age", s.backupAgeHours ?: JSONObject.NULL)
            .put("errors", JSONArray(s.errors))
            .put("warnings", JSONArray(s.warnings))
            .put("exported", exported ?: JSONObject.NULL)
            .put("chats", JSONArray(s.chats.map {
                JSONObject().put("name", it.name).put("messages", it.messages).put("media", it.media)
                    .put("missing", it.mediaMissing).put("large", it.mediaTooLarge).put("recovered", it.recovered)
                    .put("error", it.error ?: JSONObject.NULL)
            }))
        prefs.lastRun = o.toString()
    }

    /** ينسخ مجلد التجربة إلى Download/<sub>. يرجع المسار للعرض. */
    private fun exportToDownloads(dir: File, sub: String): String {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = context.contentResolver
            for (f in files) {
                val relParent = f.parentFile!!.relativeTo(dir).path
                val rel = listOf(Environment.DIRECTORY_DOWNLOADS, sub, relParent).filter { it.isNotEmpty() }.joinToString("/") + "/"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
                cr.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } else {
            @Suppress("DEPRECATION")
            val target = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub)
            dir.copyRecursively(target, overwrite = true)
        }
        return "Download/$sub"
    }

    /** كل السجلات في نص واحد (للتصدير). المفتاح محجوب أصلاً في السجلات. */
    fun allLogs(): String {
        val files = paths.logs.listFiles()?.sortedBy { it.name } ?: emptyList()
        return files.joinToString("\n") { "##### ${it.name}\n" + ArchLog.redact(it.readText()) }
    }
}
