package com.modwahi.waarchiver.core

import java.io.File
import java.util.zip.ZipInputStream

/**
 * النسخ التزايدية لواتساب (msgstore-increment-N.db.crypt15).
 *
 * واتساب صار يكتب النسخة الكاملة msgstore.db.crypt15 أحياناً فقط، وكل ليلة يضيف ملفاً تزايدياً
 * فيه التغييرات منذ الملف السابق. كل ملف (بعد فك crypt15 بنفس المفتاح) هو ZIP فيه:
 *   - messages.bin: رسالة protobuf بصيغة HistorySync (سجلات مسبوقة بطولها):
 *       2 = محادثات { 1 = jid المحادثة، 2 = رسائل { 1 = WebMessageInfo، 2 = _id الرسالة في القاعدة } }
 *       15 = ربط الرقم بمعرّف @lid { 1 = jid الرقم، 2 = jid الـ lid }
 *       18 = أسماء @lid { 1 = lid، 2 = الاسم }
 *   - ملفات JSON لباقي الجداول (لا نحتاجها للأرشفة).
 * نقرأ الرسائل فقط ونضيفها فوق القاعدة الكاملة (الأحدث يغلب عند تكرار _id).
 */
data class BackupSet(val base: SourceFile, val increments: List<SourceFile>) {
    /** وقت آخر تحديث فعلي للنسخة (الكاملة أو آخر ملف تزايدي) */
    val mtime: Double get() = maxOf(base.mtime, increments.maxOfOrNull { it.mtime } ?: 0.0)
}

private val CURRENT_INCREMENT = Regex("""msgstore-increment-(\d+)\.db\.crypt15""")

/** النسخة الكاملة + سلسلة الملفات التزايدية التابعة لها (غير المؤرّخة، والأحدث منها). */
fun SourceFs.backupSet(): BackupSet? {
    val base = backup() ?: return null
    if (base.name != "msgstore.db.crypt15") return BackupSet(base, emptyList())
    val chain = increments()
        .mapNotNull { f -> CURRENT_INCREMENT.matchEntire(f.name)?.let { it.groupValues[1].toInt() to f } }
        .filter { it.second.mtime > base.mtime }
        .sortedBy { it.first }
        .map { it.second }
    return BackupSet(base, chain)
}

/** قارئ protobuf بسيط بدون مخطط. */
class Pb(b: ByteArray, from: Int = 0, to: Int = b.size) {
    private val fields = HashMap<Int, MutableList<Any>>()

    init {
        var p = from
        while (p < to) {
            val (tag, q) = varint(b, p) ?: break
            val field = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
            p = q
            val v: Any = when (wire) {
                0 -> { val (x, r) = varint(b, p) ?: break; p = r; x }
                1 -> { if (p + 8 > to) break; var x = 0L; for (i in 7 downTo 0) x = (x shl 8) or (b[p + i].toLong() and 0xff); p += 8; x }
                5 -> { if (p + 4 > to) break; var x = 0L; for (i in 3 downTo 0) x = (x shl 8) or (b[p + i].toLong() and 0xff); p += 4; x }
                2 -> { val (l, r) = varint(b, p) ?: break; val e = r + l.toInt(); if (l < 0 || e > to) break; p = e; b.copyOfRange(r, e) }
                else -> break
            }
            fields.getOrPut(field) { ArrayList() }.add(v)
        }
    }

    fun has(n: Int) = fields.containsKey(n)
    fun all(n: Int): List<Any> = fields[n] ?: emptyList()
    fun bytes(n: Int): ByteArray? = fields[n]?.lastOrNull() as? ByteArray
    fun str(n: Int): String? = bytes(n)?.toString(Charsets.UTF_8)
    fun long(n: Int): Long? = fields[n]?.lastOrNull() as? Long
    fun bool(n: Int): Boolean = (long(n) ?: 0L) != 0L
    fun msg(n: Int): Pb? = bytes(n)?.let { Pb(it) }
    fun msgs(n: Int): List<Pb> = all(n).mapNotNull { (it as? ByteArray)?.let { b -> Pb(b) } }

    companion object {
        fun varint(b: ByteArray, start: Int): Pair<Long, Int>? {
            var r = 0L; var s = 0; var p = start
            while (p < b.size && s < 64) {
                val x = b[p++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl s)
                if (x and 0x80 == 0) return r to p
                s += 7
            }
            return null
        }
    }
}

/** رسائل الملفات التزايدية، مفهرسة بـ jid المحادثة ثم _id الرسالة. */
class IncrementOverlay {
    val byChat = HashMap<String, java.util.TreeMap<Long, Message>>()
    /** @lid → jid الرقم */
    val pnOfLid = HashMap<String, String>()
    /** @lid → الاسم الظاهر */
    val lidNames = HashMap<String, String>()
    var files = 0; private set
    val messageCount get() = byChat.values.sumOf { it.size }

    fun forChat(jid: String): Collection<Message> {
        byChat[jid]?.let { return it.values }
        // نفس المحادثة قد تكون مسجلة بالرقم في القاعدة وبالـ lid في الملف التزايدي (أو العكس)
        val alias = pnOfLid[jid] ?: pnOfLid.entries.firstOrNull { it.value == jid }?.key
        return alias?.let { byChat[it]?.values } ?: emptyList()
    }

    fun byKey(jid: String, keyId: String): Message? = forChat(jid).lastOrNull { it.keyId == keyId }

    /** يضيف ملف messages.bin (سجلات HistorySync مسبوقة بطولها). الملفات الأحدث تُضاف بعد الأقدم. */
    fun addMessagesBin(b: ByteArray) {
        files++
        var p = 0
        while (p < b.size) {
            val (len, q) = Pb.varint(b, p) ?: break
            val end = q + len.toInt()
            if (len < 0 || end > b.size) break
            addRecord(Pb(b, q, end))
            p = end
        }
    }

    private fun addRecord(rec: Pb) {
        for (m in rec.msgs(15)) {
            val pn = m.str(1); val lid = m.str(2)
            if (pn != null && lid != null) pnOfLid[lid] = pn
        }
        for (m in rec.msgs(18)) {
            val lid = m.str(1); val name = m.str(2)
            if (lid != null && !name.isNullOrBlank()) lidNames[lid] = name
        }
        for (conv in rec.msgs(2)) {
            val chatJid = conv.str(1) ?: continue
            if (chatJid == "status@broadcast") continue
            for (h in conv.msgs(2)) {
                val rowId = h.long(2) ?: continue
                val wmi = h.msg(1) ?: continue
                val msg = toMessage(wmi, chatJid, rowId) ?: continue
                byChat.getOrPut(chatJid) { java.util.TreeMap() }[rowId] = msg
            }
        }
    }

    // ---------- WebMessageInfo → Message ----------
    private class Content(
        val type: String, val text: String = "", val mime: String? = null, val name: String? = null,
        val size: Long? = null, val ctx: Pb? = null,
    ) { val isMedia get() = type in WaDb.MEDIA_TYPES }

    private val VIEW_ONCE = listOf(37, 55, 59)
    private val WRAPPERS = listOf(40, 53) // ephemeralMessage, documentWithCaptionMessage

    private fun content(m: Pb, viewOnce: Boolean = false): Content? {
        for (f in VIEW_ONCE) m.msg(f)?.msg(1)?.let { return content(it, viewOnce = true) }
        for (f in WRAPPERS) m.msg(f)?.msg(1)?.let { return content(it, viewOnce) }
        fun vo(t: String) = if (viewOnce) "view_once_$t" else t
        m.str(1)?.let { return Content("text", it) }
        m.msg(6)?.let { return Content("text", it.str(1) ?: "", ctx = it.msg(17)) }
        m.msg(3)?.let { return Content(vo("image"), it.str(3) ?: "", it.str(2), size = it.long(5), ctx = it.msg(17)) }
        m.msg(9)?.let { return Content(if (it.bool(8)) "gif" else vo("video"), it.str(7) ?: "", it.str(2), size = it.long(4), ctx = it.msg(17)) }
        m.msg(8)?.let { return Content(vo("audio"), "", it.str(2), size = it.long(4), ctx = it.msg(17)) }
        m.msg(7)?.let { return Content("document", it.str(20) ?: "", it.str(2), it.str(8) ?: it.str(3), it.long(5), it.msg(17)) }
        m.msg(26)?.let { return Content("sticker", "", it.str(5), size = it.long(9), ctx = it.msg(17)) }
        m.msg(4)?.let { return Content("contact", it.str(1) ?: "", ctx = it.msg(17)) }
        m.msg(13)?.let { return Content("contacts", it.str(1) ?: "", ctx = it.msg(17)) }
        m.msg(5)?.let { return Content("location", "", ctx = it.msg(17)) }
        m.msg(18)?.let { return Content("live_location", it.str(3) ?: "", ctx = it.msg(17)) }
        for (f in listOf(49, 60, 64)) m.msg(f)?.let { return Content("poll", it.str(2) ?: "", ctx = it.msg(5)) }
        return null // تعديل/حذف/تفاعل/أنواع داخلية: ليست رسائل للأرشيف
    }

    private fun toMessage(wmi: Pb, chatJid: String, rowId: Long): Message? {
        if ((wmi.long(24) ?: 0L) != 0L) return null // رسالة نظام (انضم/غادر/غيّر الاسم...)
        val key = wmi.msg(1) ?: return null
        val keyId = key.str(3)?.takeIf { it.isNotEmpty() } ?: return null
        val ts = wmi.long(3) ?: return null
        val c = content(wmi.msg(2) ?: return null) ?: return null
        val fromMe = key.bool(2)
        val sender = when {
            fromMe -> ""
            else -> wmi.str(5)?.takeIf { it.isNotEmpty() } ?: key.str(4)?.takeIf { it.isNotEmpty() } ?: chatJid
        }
        val ctx = c.ctx
        val quotedId = ctx?.str(1)?.takeIf { it.isNotEmpty() && ctx.has(3) }
        val quotedText = if (quotedId != null) {
            ctx.msg(3)?.let { q -> content(q)?.let { qc -> qc.text.ifEmpty { "[${qc.type}]" } } } ?: ""
        } else null
        val mediaPath = if (c.isMedia) wmi.str(38)?.takeIf { it.isNotEmpty() } else null
        return Message(
            rowId = rowId, keyId = keyId, chatJid = chatJid, timestamp = ts, fromMe = fromMe, senderJid = sender,
            type = c.type, text = c.text, mediaPath = mediaPath, mime = if (c.isMedia) c.mime else null,
            mediaName = if (c.isMedia) c.name else null, mediaSize = if (c.isMedia) c.size else null,
            quotedText = quotedText, quotedId = quotedId, forwarded = ctx?.bool(22) == true,
            pushName = wmi.str(19)?.takeIf { it.isNotEmpty() }, hasMedia = c.isMedia,
        )
    }

    companion object {
        /** يفك سلسلة الملفات التزايدية ويقرأ messages.bin من كل منها. الملف المفكوك يُحذف دائماً. */
        fun load(files: List<SourceFile>, key: String, tmp: File, log: ArchLog): IncrementOverlay {
            val ov = IncrementOverlay()
            tmp.mkdirs()
            for (f in files) {
                val plain = File(tmp, "increment.zip")
                try {
                    f.open().use { Crypt15.decrypt(it, f.size, plain, key, expectSqlite = false) }
                    var found = false
                    ZipInputStream(plain.inputStream().buffered()).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            if (e.name == "messages.bin") { ov.addMessagesBin(zip.readBytes()); found = true }
                        }
                    }
                    if (!found) log.warning("${f.name}: لا يحتوي messages.bin")
                } finally {
                    plain.delete()
                }
            }
            return ov
        }
    }
}
