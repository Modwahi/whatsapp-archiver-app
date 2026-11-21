package com.modwahi.waarchiver.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.channels.FileLock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections

const val LOG_DIR_REMOTE = "_سجل التشغيل"
const val RECENT_WINDOW_S = 10 * 60L          // إعادة فحص آخر ١٠ دقائق (تُستبعد المكررات بالـ id)
const val LOOKBACK_FLOOR_S = 3 * 86400L       // لا نرجع أكثر من ٣ أيام قبل آخر رسالة محفوظة
const val MAX_PENDING_ATTEMPTS = 7
const val MIN_BACKUP_AGE_S = 10 * 60.0
// أوزان مراحل الشريط الكلي: الفك 35٪، التجهيز 10٪، الرفع الباقي
private const val W_DECRYPT = 0.35
private const val W_PREPARE = 0.10        // لا نقرأ نسخة كُتبت للتو (قد يكون واتساب ما زال يكتبها)

open class RunError(message: String) : Exception(message)

/** تشغيل آخر يعمل الآن (ليس فشلاً: يُتخطى بهدوء) */
class BusyError : RunError("يوجد تشغيل آخر قيد العمل الآن")

data class ChatSpec(val jid: String, val name: String = "")

/**
 * تقدّم التشغيل كاملاً: المرحلة ١ فك النسخة، ٢ تجهيز المحادثات، ٣ الرفع.
 * overall نسبة العملية كلها (0..1)، و bytesDone/bytesTotal حجم الرفع الفعلي بالبايت.
 */
data class RunProgress(
    val phase: Int,
    val label: String,
    val overall: Double,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
)

data class ArchiveConfig(
    val chats: List<ChatSpec> = emptyList(),
    val firstRunDays: Int = 7,
    val maxMediaMb: Int = 100,
    val staleBackupHours: Int = 36,
    val meName: String = "أنا",
    val zone: ZoneId = ZoneId.of("Asia/Aden"),
)

/** مجلدات التطبيق الخاصة: home (الحالة والسجلات) و tmp (الفك المؤقت، داخل cache التطبيق). */
class Paths(val home: File, val tmp: File = File(home, "tmp")) {
    val state get() = File(home, "state.json")
    val logs get() = File(home, "logs")
    val lock get() = File(home, "run.lock")
}

data class ChatResult(
    val jid: String,
    var name: String = "",
    var messages: Int = 0,
    var media: Int = 0,
    var mediaMissing: Int = 0,
    var mediaTooLarge: Int = 0,
    var recovered: Int = 0,
    var error: String? = null,
)

/** Python: f"{x:.Nf}" (تقريب half-even على القيمة الثنائية الدقيقة) */
fun pyFixed(x: Double, digits: Int): String = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()

class Summary(val started: LocalDateTime, val dryRun: Boolean = false) {
    var backupAgeHours: Double? = null
    val warnings = mutableListOf<String>()
    val errors = mutableListOf<String>()
    val chats = mutableListOf<ChatResult>()

    val ok: Boolean get() = errors.isEmpty() && chats.all { it.error == null }

    fun render(): String {
        val lines = mutableListOf(
            "=== تشغيل ${started.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}" +
                (if (dryRun) " (تجربة dry-run)" else "") + " ==="
        )
        backupAgeHours?.let { lines.add("عمر ملف النسخة الاحتياطية: ${pyFixed(it, 1)} ساعة") }
        for (w in warnings) lines.add("⚠️ تحذير: $w")
        for (c in chats) {
            if (c.error != null) {
                lines.add("✗ ${c.name.ifEmpty { c.jid }}: خطأ — ${c.error}")
            } else {
                val extra = mutableListOf<String>()
                if (c.mediaMissing != 0) extra.add("وسائط غير منزّلة ${c.mediaMissing}")
                if (c.mediaTooLarge != 0) extra.add("وسائط أكبر من الحد ${c.mediaTooLarge}")
                if (c.recovered != 0) extra.add("وسائط مستردة من تشغيل سابق ${c.recovered}")
                lines.add("✓ ${c.name}: رسائل ${c.messages} | وسائط ${c.media}" +
                    if (extra.isNotEmpty()) " | " + extra.joinToString(" | ") else "")
            }
        }
        for (e in errors) lines.add("✗ خطأ: $e")
        val totalM = chats.sumOf { it.messages }
        val totalF = chats.sumOf { it.media }
        lines.add("المجموع: $totalM رسالة، $totalF وسيط. الحالة: ${if (ok) "ناجح" else "فيه أخطاء"}")
        return lines.joinToString("\n") + "\n"
    }
}

/** قفل تشغيل واحد: قفل ملف بين العمليات، ومجموعة داخل العملية نفسها. */
object RunLock {
    private val held = Collections.synchronizedSet(HashSet<String>())

    fun <T> with(path: File, block: () -> T): T {
        path.parentFile?.mkdirs()
        val key = path.canonicalPath
        if (!held.add(key)) throw BusyError()
        try {
            RandomAccessFile(path, "rw").use { raf ->
                val lock: FileLock = try { raf.channel.tryLock() } catch (e: Exception) { null }
                    ?: throw BusyError()
                try {
                    return block()
                } finally {
                    try { lock.release() } catch (_: IOException) {}
                }
            }
        } finally {
            held.remove(key)
        }
    }
}

fun removeDbFiles(dbPath: File) {
    for (suffix in listOf("", "-journal", "-wal", "-shm")) File(dbPath.path + suffix).delete()
}

/** يفك النسخة إلى مجلد مؤقت، ويحذف الملف المفكوك دائماً في النهاية (حتى عند الفشل). */
fun <T> withDecryptedDb(source: SourceFs, paths: Paths, key: String, log: ArchLog, opener: SqlOpener,
                        onProgress: ((Double) -> Unit)? = null, block: (WaDb) -> T): T {
    val src = source.backup() ?: throw RunError(
        "لم أجد ملف النسخة الاحتياطية:\n  ${source.backupDescription}\n" +
            "تأكد من اختيار مجلد واتساب الصحيح، ومن تفعيل النسخ الاحتياطي في واتساب."
    )
    paths.tmp.mkdirs()
    val dbPath = File(paths.tmp, "msgstore.db")
    removeDbFiles(dbPath) // بقايا تشغيل سابق انقطع
    val size = src.size
    val free = paths.tmp.usableSpace
    val need = size * 5 + 200L * 1024 * 1024
    if (free < need) {
        throw RunError("المساحة الفارغة غير كافية لفك النسخة: متوفر ${free / (1 shl 20)}MB، مطلوب تقريباً ${need / (1 shl 20)}MB")
    }
    var db: WaDb? = null
    try {
        val t = System.nanoTime()
        log.info("فك النسخة الاحتياطية (${size / (1 shl 20)}MB)...")
        val out = src.open().use { Crypt15.decrypt(it, size, dbPath, key, onProgress) }
        log.info("تم الفك (${out / (1 shl 20)}MB) في ${pyFixed((System.nanoTime() - t) / 1e9, 0)} ثانية")
        db = WaDb(dbPath, opener)
        val chain = runCatching { source.backupSet()?.increments }.getOrNull().orEmpty()
        if (chain.isNotEmpty()) {
            // خطأ في الملفات التزايدية لا يُفشل التشغيل: نكمل بالنسخة الكاملة وحدها
            try {
                val ov = IncrementOverlay.load(chain, key, File(paths.tmp, "inc"), log)
                db.overlay = ov
                log.info("النسخ التزايدية: ${chain.joinToString(", ") { it.name }} — ${ov.messageCount} رسالة")
            } catch (e: Exception) {
                log.warning("تعذّر قراءة النسخ التزايدية (${e.message}) — أكمل بالنسخة الكاملة فقط")
            }
        }
        return block(db)
    } finally {
        db?.close()
        removeDbFiles(dbPath)
    }
}

class Archiver(
    private val cfg: ArchiveConfig,
    private val source: SourceFs,
    private val uploader: Uploader,
    private val log: ArchLog,
    contacts: Map<String, String> = emptyMap(),
    private val dryRun: Boolean = false,
    private val now: Double,
) {
    private val contacts: Map<String, String> = contacts.mapKeys { Naming.normalizeNumber(it.key) }
    private val zone get() = cfg.zone

    // ---------- الأسماء ----------
    companion object {
        fun numberOf(jid: String): String =
            if (jid.endsWith("@s.whatsapp.net") || jid.endsWith("@c.us"))
                Naming.normalizeNumber(jid.split("@")[0].split(":")[0]) else ""
    }

    /** (الاسم، الرقم) */
    fun senderParts(msg: Message): Pair<String, String> {
        if (msg.fromMe) return cfg.meName to ""
        val number = numberOf(msg.senderJid)
        var name = if (number.isNotEmpty()) contacts[number] ?: "" else ""
        if (name.isEmpty() && !msg.pushName.isNullOrEmpty()) {
            name = "~" + msg.pushName // ~ تعني اسماً وضعه الشخص لنفسه (ليس من جهات الاتصال)
        }
        if (number.isEmpty() && name.isEmpty()) {
            name = if (msg.senderJid.isNotEmpty()) msg.senderJid.split("@")[0] else "مجهول"
        }
        return name to number
    }

    fun senderDisplay(msg: Message): String {
        val (name, number) = senderParts(msg)
        if (name.isNotEmpty() && number.isNotEmpty()) return "$name ($number)"
        return name.ifEmpty { number }
    }

    fun chatFolder(specName: String, chat: ChatInfo): String {
        var name = specName.ifEmpty { chat.subject }
        if (name.isEmpty()) {
            val number = numberOf(chat.jid)
            name = contacts[number]?.takeIf { it.isNotEmpty() } ?: number.ifEmpty { chat.jid }
        }
        return Naming.sanitizeName(name)
    }

    // ---------- السجل ----------
    fun buildRecord(msg: Message): JObj = JObj().apply {
        put("id", msg.keyId)
        put("row_id", msg.rowId)
        put("timestamp", msg.timestamp)
        put("time", Naming.isoLocal(msg.timestamp, zone))
        put("chat_jid", msg.chatJid)
        put("sender", senderDisplay(msg))
        put("sender_jid", msg.senderJid)
        put("from_me", msg.fromMe)
        put("type", msg.type)
        put("text", msg.text)
        put("has_media", msg.hasMedia)
        put("media_file", null)
        put("media_status", null)
        put("mime", msg.mime)
        put("quoted_id", msg.quotedId)
        put("quoted_text", msg.quotedText)
        put("forwarded", msg.forwarded)
    }

    /** ينسخ الوسيط (قراءة فقط من مجلد واتساب) إلى مجلد اليوم المؤقت. يرجع الحالة. */
    fun copyMedia(msg: Message, rec: JObj, dayDir: File): String {
        val rel = msg.mediaPath
        if (!rel.isNullOrEmpty()) rec["media_path"] = rel // للتشخيص: المسار المسجّل في قاعدة واتساب
        if (rel.isNullOrEmpty()) {
            rec["media_status"] = "not_downloaded" // واتساب لم يسجّل مساراً: لم يُنزَّل وقت النسخة
            return "not_downloaded"
        }
        val src = try { source.media(rel) } catch (e: Exception) { null }
        if (src == null) {
            rec["media_status"] = "missing" // مسجّل لكن الملف غير موجود (حُذف/نُقل أو لا صلاحية)
            log.warning("وسيط مسجّل لكنه غير موجود: $rel")
            return "missing"
        }
        if (src.size > cfg.maxMediaMb.toLong() * 1024 * 1024) {
            rec["media_status"] = "too_large"
            return "too_large"
        }
        val (name, number) = senderParts(msg)
        val fname = Naming.mediaFileName(msg.timestamp, name.trimStart('~').ifEmpty { number }, msg.keyId, msg.mime,
            zone, msg.mediaName, src.name)
        dayDir.mkdirs()
        try {
            src.open().use { inp -> File(dayDir, fname).outputStream().use { out -> inp.copyTo(out, 256 * 1024) } }
        } catch (e: Exception) {
            if (e !is IOException && e !is SecurityException) throw e
            log.warning("تعذّر نسخ الوسيط ${src.name}: ${e.message}")
            rec["media_status"] = "error"
            return "error"
        }
        rec["media_file"] = fname
        rec["media_status"] = "saved"
        return "saved"
    }

    private fun hasMediaFile(rel: String?): Boolean {
        if (rel.isNullOrEmpty()) return false
        return try { source.media(rel) != null } catch (e: Exception) { false }
    }

    // ---------- محادثة واحدة ----------
    @Suppress("UNCHECKED_CAST")
    fun prepareChat(db: WaDb, spec: ChatSpec, state: State, staging: File): Prepared {
        val res = ChatResult(jid = spec.jid, name = spec.name.ifEmpty { spec.jid })
        val chat = db.findChat(spec.jid)
            ?: throw RunError("المحادثة غير موجودة في النسخة الاحتياطية (ربما حُذفت أو غُيّرت)")
        val folder = chatFolder(spec.name, chat)
        res.name = folder
        val cs = state.chat(spec.jid)
        val lastTs = cs["last_timestamp"].asLong()
        val lastRow = cs["last_row_id"].asLong()
        val msgs = if (lastTs != 0L) {
            db.messages(chat, sinceMs = (lastTs - LOOKBACK_FLOOR_S) * 1000, afterRowId = lastRow,
                recentMs = (lastTs - RECENT_WINDOW_S) * 1000)
        } else {
            val since = Math.floor(now - cfg.firstRunDays * 86400.0).toLong()
            db.messages(chat, sinceMs = since * 1000)
        }

        val chatStage = File(staging, folder)
        val days = HashMap<String, DayFile>()
        val touched = sortedSetOf<String>()

        fun day(date: String): DayFile = days.getOrPut(date) {
            // نقرأ ملف اليوم من الوجهة (إن وُجد) لندمج معه دون تكرار
            DayFile.fromJsonBytes(uploader.readFile("$folder/$date", JSON_NAME))
        }

        val newPending = mutableListOf<JObj>()
        var maxRow = lastRow
        var maxTs = lastTs
        for (msg in msgs) {
            maxRow = maxOf(maxRow, msg.rowId)
            maxTs = maxOf(maxTs, msg.timestamp)
            val date = Naming.dateKey(msg.timestamp, zone)
            val d = day(date)
            if (d.has(msg.keyId)) continue
            val rec = buildRecord(msg)
            if (msg.hasMedia) {
                when (copyMedia(msg, rec, File(chatStage, date))) {
                    "saved" -> res.media++
                    "too_large" -> res.mediaTooLarge++
                    else -> {
                        res.mediaMissing++
                        newPending.add(JObj().apply { put("id", msg.keyId); put("date", date); put("attempts", 0L) })
                    }
                }
            }
            d.add(listOf(rec))
            touched.add(date)
            res.messages++
        }

        // وسائط لم تكن منزّلة في تشغيل سابق: هل نزلت الآن؟
        val kept = mutableListOf<Any?>()
        for (pAny in cs["pending_media"] as List<Any?>) {
            val p = pAny as JObj
            val pid = p["id"].toString()
            val pdate = p["date"].toString()
            val msg = db.messageByKey(chat, pid)
            if (msg != null && hasMediaFile(msg.mediaPath)) {
                val rec = JObj()
                if (copyMedia(msg, rec, File(chatStage, pdate)) == "saved") {
                    day(pdate).update(pid, linkedMapOf("media_file" to rec["media_file"], "media_status" to "saved"))
                    touched.add(pdate)
                    res.recovered++
                    res.media++
                    continue
                }
            }
            p["attempts"] = p["attempts"].asLong() + 1
            if (p["attempts"].asLong() < MAX_PENDING_ATTEMPTS) kept.add(p)
            else log.info("[$folder] توقف انتظار الوسيط $pid بعد ${p["attempts"]} محاولات")
        }

        for (date in touched) days.getValue(date).write(File(chatStage, date), zone)

        // بعد نجاح الرفع فقط نحدّث الحالة
        val commit = {
            cs["last_row_id"] = maxRow
            cs["last_timestamp"] = maxTs
            cs["pending_media"] = ArrayList(kept + newPending)
            cs["folder"] = folder
            if (!dryRun) {
                state.save()
                chatStage.deleteRecursively()
            }
        }
        return Prepared(res, folder, chatStage, touched.isNotEmpty() && !dryRun, commit)
    }

    /** محادثة جاهزة للرفع: ملفاتها في stage، و commit يحدّث الحالة بعد نجاح الرفع. */
    class Prepared(val res: ChatResult, val folder: String, val stage: File, val needsUpload: Boolean, val commit: () -> Unit) {
        val bytes: Long get() = if (needsUpload) stage.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    /** تجهيز ثم رفع محادثة واحدة (نفس ترتيب الأداة) */
    fun processChat(db: WaDb, spec: ChatSpec, state: State, staging: File): ChatResult {
        val p = prepareChat(db, spec, state, staging)
        if (p.needsUpload) uploader.uploadDir(p.stage, p.folder)
        p.commit()
        return p.res
    }
}

/**
 * التشغيل: فك النسخة، استخراج المحادثات المحددة فقط، الكتابة، الرفع، ثم حذف الملف المفكوك.
 * يرجع الملخص، أو null إن تم تخطي التشغيل (لا نسخة جديدة في الوضع المجدول).
 */
fun runArchive(
    cfg: ArchiveConfig, paths: Paths, key: String, source: SourceFs, uploader: Uploader, log: ArchLog,
    opener: SqlOpener, contacts: Map<String, String> = emptyMap(), dryRun: Boolean = false,
    scheduled: Boolean = false, dryDir: File? = null, now: Double = System.currentTimeMillis() / 1000.0,
    onProgress: ((RunProgress) -> Unit)? = null,
    /** يُستدعى بقائمة كل المحادثات بعد الفك (ليتحدث عرض المحادثات بدون فك إضافي). */
    onChats: ((List<ChatInfo>) -> Unit)? = null,
): Summary? {
    val state = State(paths.state)
    val startedAt = Naming.localDt(Math.floor(now).toLong(), cfg.zone)
    val summary = Summary(startedAt, dryRun)
    return RunLock.with(paths.lock) {
        if (cfg.chats.isEmpty()) throw RunError("لم تختر أي محادثة بعد. اختر المحادثات من شاشة «المحادثات».")
        val backup = source.backup()
        val mtime = runCatching { source.backupSet()?.mtime }.getOrNull() ?: backup?.mtime
        if (scheduled && mtime == null) {
            // لا نكرر تقرير «لا توجد نسخة» كل ساعة: مرة واحدة في اليوم تكفي
            val today = startedAt.toLocalDate().toString()
            if (state.data["missing_backup_reported"] == today) return@with null
            state.data["missing_backup_reported"] = today
            state.save()
        }
        if (scheduled && mtime != null) {
            if (mtime <= state.lastBackupMtime) {
                log.info("لا توجد نسخة احتياطية جديدة منذ آخر تشغيل — لا شيء للعمل")
                return@with null
            }
            if (now - mtime < MIN_BACKUP_AGE_S) {
                log.info("النسخة الاحتياطية حديثة جداً (قد تكون قيد الكتابة) — سأحاول لاحقاً")
                return@with null
            }
        }
        backup?.let { log.info("ملف النسخة المستخدم: ${it.name}") }
        summary.backupAgeHours = mtime?.let { (now - it) / 3600 }
        summary.backupAgeHours?.let { age ->
            if (age > cfg.staleBackupHours) {
                summary.warnings.add("النسخة الاحتياطية لم تتحدث منذ ${pyFixed(age, 0)} ساعة " +
                    "(تأكد أن النسخ الاحتياطي اليومي مفعّل في واتساب)")
                log.warning(summary.warnings.last())
            }
        }

        val staging = if (dryRun) dryDir ?: throw RunError("مجلد التجربة غير محدد") else File(paths.tmp, "out")
        if (!dryRun) staging.deleteRecursively()
        staging.mkdirs()
        val archiver = Archiver(cfg, source, uploader, log, contacts, dryRun, now)
        try {
            // المرحلة ١ و ٢: فك النسخة وتجهيز كل المحادثات (القاعدة المفكوكة تُحذف قبل الرفع)
            val slots = arrayOfNulls<ChatResult>(cfg.chats.size)
            val prepared = ArrayList<Pair<Int, Archiver.Prepared>>()
            withDecryptedDb(source, paths, key, log, opener, {
                onProgress?.invoke(RunProgress(1, "فك النسخة الاحتياطية", W_DECRYPT * it))
            }) { db ->
                onProgress?.invoke(RunProgress(2, "قراءة المحادثات", W_DECRYPT))
                onChats?.let { cb -> runCatching { cb(db.listChatsCached()) } }
                cfg.chats.forEachIndexed { i, spec ->
                    onProgress?.invoke(RunProgress(2, "تجهيز المحادثة ${i + 1} من ${cfg.chats.size}",
                        W_DECRYPT + W_PREPARE * i / cfg.chats.size))
                    try {
                        prepared.add(i to archiver.prepareChat(db, spec, state, staging))
                    } catch (e: Exception) { // خطأ في محادثة لا يوقف الباقي
                        slots[i] = ChatResult(jid = spec.jid, name = spec.name.ifEmpty { spec.jid },
                            error = e.message ?: e.javaClass.simpleName).also { log.error("[${it.name}] فشل: ${it.error}") }
                    }
                }
            }
            // المرحلة ٣: الرفع، مع الحجم الكلي الدقيق مسبقاً
            val total = prepared.sumOf { it.second.bytes }
            var done = 0L
            val doneLock = Any()
            val base = W_DECRYPT + W_PREPARE
            fun upProgress(label: String) = onProgress?.invoke(RunProgress(3, label,
                if (total > 0) base + (1 - base) * done.toDouble() / total else 1.0, done, total))
            if (total > 0) {
                log.info("الرفع: ${prepared.count { it.second.needsUpload }} محادثة، ${total / 1024} كيلوبايت")
                upProgress("رفع")
            }
            for ((i, p) in prepared) {
                try {
                    if (p.needsUpload) {
                        val before = done
                        val chatBytes = p.bytes
                        uploader.uploadDir(p.stage, p.folder) { delta ->
                            synchronized(doneLock) { done = (done + delta).coerceIn(before, before + chatBytes) }
                            upProgress("رفع: ${p.res.name}")
                        }
                        done = before + chatBytes
                    }
                    p.commit()
                    slots[i] = p.res.also { log.info("[${it.name}] رسائل ${it.messages} | وسائط ${it.media} | غير منزّلة ${it.mediaMissing}") }
                } catch (e: Exception) {
                    slots[i] = ChatResult(jid = cfg.chats[i].jid, name = cfg.chats[i].name.ifEmpty { cfg.chats[i].jid },
                        error = e.message ?: e.javaClass.simpleName).also { log.error("[${it.name}] فشل: ${it.error}") }
                }
            }
            slots.forEach { r -> if (r != null) summary.chats.add(r) }
            onProgress?.invoke(RunProgress(3, "اكتمل", 1.0, done, total))
            if (summary.ok && !dryRun && mtime != null) {
                state.lastBackupMtime = mtime
                state.save()
            }
        } catch (e: Exception) {
            summary.errors.add(e.message ?: e.javaClass.simpleName)
            log.error(summary.errors.last())
        } finally {
            if (!dryRun) staging.deleteRecursively()
            writeSummary(summary, paths, uploader, log, dryRun)
        }
        summary
    }
}

fun writeSummary(summary: Summary, paths: Paths, uploader: Uploader, log: ArchLog, dryRun: Boolean) {
    val text = summary.render()
    log.info("الملخص:\n$text")
    paths.logs.mkdirs()
    val name = summary.started.toLocalDate().toString() + ".txt"
    val local = File(paths.logs, "summary-$name")
    local.appendText(text + "\n", Charsets.UTF_8)
    if (dryRun) return
    try {
        val up = File(paths.tmp, "summary")
        up.deleteRecursively()
        up.mkdirs()
        local.copyTo(File(up, name), overwrite = true)
        uploader.uploadDir(up, LOG_DIR_REMOTE)
        up.deleteRecursively()
    } catch (e: Exception) {
        log.error("تعذّر رفع ملخص التشغيل: ${e.message}")
    }
}
