package com.modwahi.waarchiver.work

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.modwahi.waarchiver.core.ArchLog
import com.modwahi.waarchiver.core.ChatInfo
import com.modwahi.waarchiver.core.ChatSpec
import com.modwahi.waarchiver.core.Crypt15
import com.modwahi.waarchiver.core.FileLog
import com.modwahi.waarchiver.core.JObj
import com.modwahi.waarchiver.core.Json
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
import com.modwahi.waarchiver.io.PhoneContacts
import com.modwahi.waarchiver.io.SafSource
import com.modwahi.waarchiver.io.SafUploader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** أوقف المستخدم التشغيل */
class StoppedByUser : RuntimeException("أوقفته يدوياً")

/** حالة أرشفة محادثة واحدة (من state.json) للعرض. */
data class ChatProgress(val lastTimestamp: Long, val pendingMedia: Int, val folder: String?)

/** يجمع أجزاء التشغيل: الإعدادات، المفتاح، مصدر SAF، الرافع (Drive أو محلي)، والسجلات. */
class Engine(private val context: Context) {
    val prefs = Prefs(context)
    val vault = KeyVault(context)
    val paths = Paths(home = File(context.filesDir, "archiver"), tmp = File(context.cacheDir, "work"))
    val log: ArchLog = FileLog(paths.logs)
    val mirror = Mirror(File(context.filesDir, "mirror"))

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
            log.warning("مجلد الدرايف المحفوظ لم يعد موجوداً")
        }
        // لا يوجد مجلد محفوظ (مثلاً بعد إعادة تثبيت التطبيق): نواصل في مجلد الأرشيف السابق إن وُجد
        runCatching { drive.archiveRoots(DRIVE_FOLDER_NAME, com.modwahi.waarchiver.core.LOG_DIR_REMOTE) }.getOrNull()
            ?.firstOrNull()?.let { prev ->
                prefs.driveFolderId = prev.id
                log.info("وجدت مجلد الأرشيف السابق «${prev.name}» وسأواصل فيه")
                return prev.id
            }
        val id = drive.createFolder(DRIVE_FOLDER_NAME, null)
        prefs.driveFolderId = id
        log.info("أنشأت مجلد «$DRIVE_FOLDER_NAME» في Google Drive")
        return id
    }

    /** مجلدات الأرشيف في الدرايف التي يستطيع التطبيق الكتابة فيها */
    fun archiveRoots(): List<DriveClient.ArchiveRoot> =
        driveClient().archiveRoots(DRIVE_FOLDER_NAME, com.modwahi.waarchiver.core.LOG_DIR_REMOTE)

    /**
     * يستخدم مجلداً آخر في الدرايف (من القائمة أو برابطه). يُعاد حساب التقدّم: التشغيل التالي يسحب آخر
     * «أيام السحب أول مرة» ويدمجها مع ما في المجلد بدون تكرار (حسب رقم كل رسالة).
     */
    fun useDriveFolder(idOrLink: String): String {
        val id = Regex("folders/([A-Za-z0-9_-]+)").find(idOrLink)?.groupValues?.get(1)
            ?: idOrLink.trim().takeIf { Regex("[A-Za-z0-9_-]{10,}").matches(it) }
            ?: throw RunError("هذا ليس رابط مجلد في Google Drive")
        val f = driveClient().get(id) ?: throw RunError(
            "التطبيق لا يستطيع الوصول لهذا المجلد. صلاحية التطبيق (drive.file) تسمح فقط بالمجلدات التي أنشأها التطبيق نفسه — " +
                "مجلد أداة Termux مثلاً لا يمكن الكتابة فيه.")
        if (f.mime != DriveClient.FOLDER_MIME) throw RunError("هذا الرابط لملف وليس لمجلد")
        if (f.trashed) throw RunError("هذا المجلد في سلة المهملات")
        if (id != prefs.driveFolderId) {
            prefs.driveFolderId = id
            resetProgress()
            log.info("مجلد الأرشيف الآن: «${f.name}»")
        }
        return f.name
    }

    /** ينسى آخر رسالة أُرشفت لكل محادثة؛ التشغيل التالي يكمل بالدمج مع ما في الوجهة بدون تكرار */
    fun resetProgress() {
        paths.state.delete()
        prefs.failState = null
    }

    private class LazyUploader(private val make: () -> Uploader) : Uploader {
        // يُجهَّز عند أول استخدام، حتى يظهر خطأ الاتصال في ملخص المحادثة كما في الأداة
        private val holder = lazy { make() }
        val inner by holder
        fun closeIfOpen() { if (holder.isInitialized()) (holder.value as? DriveUploader)?.close() }
        override fun readFile(relDir: String, name: String) = inner.readFile(relDir, name)
        override fun uploadDir(localDir: File, relDir: String) = inner.uploadDir(localDir, relDir)
        override fun uploadDir(localDir: File, relDir: String, onBytes: (Long) -> Unit) = inner.uploadDir(localDir, relDir, onBytes)
    }

    private fun uploader(): Uploader = when (prefs.mode) {
        Mode.LOCAL -> SafUploader(context, Uri.parse(prefs.localTreeUri
            ?: throw RunError("لم تختر مجلد الحفظ المحلي (الإعدادات ← وجهة الحفظ).")))
        Mode.DRIVE -> LazyUploader {
            val d = driveClient()
            DriveUploader(d, ensureDriveFolder(d))
        }
    }

    /** فك تجريبي سريع لأول جزء من النسخة. */
    fun verifyKey(key: String) {
        val b = source().backup() ?: throw RunError("لم أجد ملف النسخة الاحتياطية Databases/msgstore.db.crypt15 في المجلد المختار.")
        b.open().use { Crypt15.verifyKey(it, key) }
    }

    /**
     * فحص سريع قبل التشغيل المجدول (بدون فك): نفس شروط الأداة.
     * يرجع سبب التخطي، أو null إن كان هناك عمل.
     */
    fun scheduledSkipReason(): String? {
        val b = runCatching { source().backup() }.getOrNull() ?: return null // عدم وجود النسخة يعالجه التشغيل نفسه
        val last = com.modwahi.waarchiver.core.State(paths.state).lastBackupMtime
        if (b.mtime <= last) return "لا توجد نسخة احتياطية جديدة منذ آخر أرشفة"
        if (System.currentTimeMillis() / 1000.0 - b.mtime < com.modwahi.waarchiver.core.MIN_BACKUP_AGE_S)
            return "النسخة الاحتياطية كُتبت للتو — سأحاول بعد قليل"
        return null
    }

    fun backupMtimeMs(): Long? = runCatching { source().backup()?.mtime?.let { (it * 1000).toLong() } }.getOrNull()

    fun backupAgeHours(): Double? = backupMtimeMs()?.let { (System.currentTimeMillis() - it) / 3_600_000.0 }

    /** الأسماء: جهات الاتصال (إن فُعّلت) ثم الأسماء التي كتبتها في التطبيق (لها الأولوية). */
    fun contacts(): Map<String, String> {
        val phone = if (prefs.importContacts) runCatching { PhoneContacts.read(context, prefs.countryCode) }.getOrDefault(emptyMap()) else emptyMap()
        return phone + prefs.contacts
    }

    /** حالة كل محادثة من state.json (آخر رسالة مؤرشفة، وسائط معلّقة، اسم المجلد المستخدم). */
    @Suppress("UNCHECKED_CAST")
    fun chatProgress(): Map<String, ChatProgress> = runCatching {
        val st = Json.parse(paths.state.readText()) as JObj
        (st["chats"] as JObj).mapValues { (_, v) ->
            val c = v as JObj
            ChatProgress((c["last_timestamp"] as? Number)?.toLong() ?: 0, (c["pending_media"] as? List<*>)?.size ?: 0, c["folder"] as? String)
        }
    }.getOrDefault(emptyMap())

    private fun saveKnown(chats: List<ChatInfo>) {
        prefs.knownChats = chats.map { KnownChat(it.jid, it.subject, it.isGroup, it.messageCount, it.lastTimestamp, it.phone, it.pushName) }
        prefs.knownChatsAt = System.currentTimeMillis()
    }

    /** يفك النسخة ويقرأ قائمة المحادثات (لشاشة المحادثات)، ثم يحذف القاعدة المفكوكة. */
    fun listChats(progress: (String, Double?) -> Unit): List<ChatInfo> = RunLock.with(paths.lock) {
        withDecryptedDb(source(), paths, key(), log, AndroidSql, { progress("فك النسخة الاحتياطية", it) }) { db ->
            progress("قراءة المحادثات", null)
            db.listChats()
        }
    }.also(::saveKnown)

    /** التشغيل الحقيقي أو التجربة. يرجع null إن تم التخطي (لا نسخة جديدة في الوضع المجدول). */
    fun run(scheduled: Boolean, dryRun: Boolean, progress: (com.modwahi.waarchiver.core.RunProgress) -> Unit): Pair<Summary?, String?> {
        val base = prefs.archiveConfig()
        // اسم المجلد ثابت: إن لم تكتب اسماً نستخدم الاسم الذي استُخدم أول مرة، حتى لا يتغير المجلد
        // لاحقاً إن أضفت اسم صاحب الرقم أو تغيّر اسم المجموعة
        val used = chatProgress()
        val names = contacts()
        val known = prefs.knownChats.associateBy { it.jid }
        val cfg = base.copy(chats = base.chats.map { c ->
            if (c.name.isNotEmpty()) c
            else ChatSpec(c.jid, used[c.jid]?.folder?.takeIf { it.isNotEmpty() } ?: lidFolderName(known[c.jid], names))
        })
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(Date())
        val dryDir = File(context.cacheDir, "dry/$stamp")
        val up: Uploader = if (dryRun) {
            // التجربة لا تقرأ ولا تكتب في الوجهة (مثل الأداة)
            object : Uploader {
                override fun readFile(relDir: String, name: String): ByteArray? = null
                override fun uploadDir(localDir: File, relDir: String) {}
            }
        } else MirroringUploader(uploader(), mirror)
        val startedAt = System.currentTimeMillis()
        var uploaded = 0L
        val summary = try {
            runArchive(cfg, paths, key(), source(), up, log, AndroidSql, contacts(),
                dryRun = dryRun, scheduled = scheduled, dryDir = if (dryRun) dryDir else null,
                onProgress = { p -> if (p.phase == 3) uploaded = p.bytesDone; progress(p) }, onChats = ::saveKnown)
        } finally {
            (up as? LazyUploader)?.closeIfOpen()
            ((up as? MirroringUploader)?.inner as? LazyUploader)?.closeIfOpen()
        }
        var exported: String? = null
        if (dryRun && summary != null) {
            File(dryDir, "_ملخص التجربة.txt").writeText(summary.render())
            exported = exportToDownloads(dryDir, "أرشيف واتساب - تجربة/$stamp")
            dryDir.deleteRecursively()
        }
        if (summary != null) saveRun(summary, exported, scheduled, uploaded, System.currentTimeMillis() - startedAt)
        return summary to exported
    }

    /**
     * محادثة خاصة بمعرّف @lid: الأداة كانت ستسمي مجلدها بالمعرّف (145479585235031@lid).
     * نعطيها اسماً مفهوماً: اسم جهة الاتصال، أو الاسم الذي وضعه الشخص لنفسه، أو الرقم الحقيقي.
     */
    private fun lidFolderName(k: KnownChat?, names: Map<String, String>): String {
        if (k == null || !k.jid.endsWith("@lid")) return ""
        return names[k.phone]?.takeIf { it.isNotBlank() } ?: k.pushName.ifEmpty { k.phone }
    }

    /** ينزّل ملفات الرسائل.json من الوجهة إلى نسخة القراءة (لما أُرشف قبل تفعيل القارئ). */
    fun syncMirror(progress: (String, Double?) -> Unit): Int {
        val (list, read) = when (prefs.mode) {
            Mode.LOCAL -> {
                val u = SafUploader(context, Uri.parse(prefs.localTreeUri ?: throw RunError("لم تختر مجلد الحفظ المحلي.")))
                Pair({ rel: String -> u.list(rel) }, { rel: String, n: String -> u.readFile(rel, n) })
            }
            Mode.DRIVE -> {
                val d = driveClient()
                val u = DriveUploader(d, ensureDriveFolder(d))
                Pair({ rel: String -> u.list(rel) }, { rel: String, n: String -> u.readFile(rel, n) })
            }
        }
        val dateRe = Regex("\\d{4}-\\d{2}-\\d{2}")
        val folders = list("").filter { it.isDir && it.name != com.modwahi.waarchiver.core.LOG_DIR_REMOTE }
        var fetched = 0
        folders.forEachIndexed { i, f ->
            progress("تنزيل للقراءة: ${f.name}", i.toDouble() / folders.size)
            for (day in list(f.name).filter { it.isDir && dateRe.matches(it.name) }) {
                val json = list("${f.name}/${day.name}").firstOrNull { !it.isDir && it.name == com.modwahi.waarchiver.core.JSON_NAME } ?: continue
                if (json.size > 0 && json.size == mirror.size(f.name, day.name)) continue
                read("${f.name}/${day.name}", json.name)?.let { mirror.save(f.name, day.name, it); fetched++ }
            }
        }
        return fetched
    }

    private fun saveRun(s: Summary, exported: String?, scheduled: Boolean, bytes: Long, durationMs: Long) {
        val o = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("bytes", bytes)
            .put("duration", durationMs)
            .put("ok", s.ok)
            .put("dry", s.dryRun)
            .put("scheduled", scheduled)
            .put("text", s.render())
            .put("age", s.backupAgeHours ?: JSONObject.NULL)
            .put("errors", JSONArray(s.errors))
            .put("warnings", JSONArray(s.warnings))
            .put("exported", exported ?: JSONObject.NULL)
            .put("chats", JSONArray(s.chats.map {
                JSONObject().put("name", it.name).put("jid", it.jid).put("messages", it.messages).put("media", it.media)
                    .put("missing", it.mediaMissing).put("large", it.mediaTooLarge).put("recovered", it.recovered)
                    .put("error", it.error ?: JSONObject.NULL)
            }))
        prefs.lastRun = o.toString()
        prefs.history = listOf(o) + prefs.history
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
        val crash = File(context.filesDir, "last_crash.txt").takeIf { it.exists() }?.let { "##### last_crash.txt\n" + it.readText() + "\n" } ?: ""
        return crash + files.joinToString("\n") { "##### ${it.name}\n" + ArchLog.redact(it.readText()) }
    }
}
