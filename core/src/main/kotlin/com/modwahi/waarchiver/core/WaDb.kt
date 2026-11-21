package com.modwahi.waarchiver.core

import java.io.File

class SchemaError(message: String) : Exception(message)

/** صف نتيجة: القيم Long / Double / String / ByteArray / null بأسماء الأعمدة. */
class Row(private val values: Map<String, Any?>, private val ordered: List<Any?>) {
    operator fun get(name: String): Any? = values[name]
    operator fun get(index: Int): Any? = ordered[index]
}

/** اتصال SQLite مجرد: أندرويد (android.database.sqlite) في التطبيق، و sqlite-jdbc في الاختبارات. */
interface SqlDb : AutoCloseable {
    fun query(sql: String, args: List<Any?> = emptyList()): List<Row>
    override fun close()
}

interface SqlOpener {
    /** فتح للكتابة (لإنشاء الفهرس على النسخة المؤقتة فقط). */
    fun openReadWrite(file: File): SqlDb
    fun openReadOnly(file: File): SqlDb
    fun exec(db: SqlDb, sql: String)
}

data class ChatInfo(
    val rowId: Long,
    val jid: String,
    val subject: String,
    val isGroup: Boolean,
    val messageCount: Long = 0,
    val lastTimestamp: Long = 0, // ثوانٍ
    /** للعرض فقط: الرقم الحقيقي (أرقام فقط) — يُستخرج من jid_map لمحادثات @lid */
    val phone: String = "",
    /** للعرض فقط: الاسم الذي وضعه الشخص لنفسه (push name) */
    val pushName: String = "",
)

data class Message(
    val rowId: Long,
    val keyId: String,
    val chatJid: String,
    val timestamp: Long, // ثوانٍ
    val fromMe: Boolean,
    val senderJid: String,
    val type: String,
    val text: String,
    val mediaPath: String? = null, // نسبي لمجلد WhatsApp
    val mime: String? = null,
    val mediaName: String? = null,
    val mediaSize: Long? = null,
    val quotedText: String? = null,
    val quotedId: String? = null,
    val forwarded: Boolean = false,
    val pushName: String? = null,
    val hasMedia: Boolean = false,
)

/**
 * قراءة قاعدة msgstore.db (مخطط واتساب الحديث) بشكل دفاعي — منقول من wadb.py.
 * قبل أي استعلام نفحص الجداول والأعمدة. الأعمدة الأساسية إن غابت نرمي SchemaError،
 * والجداول/الأعمدة الاختيارية نتجاهلها إن غابت.
 */
class WaDb(path: File, private val opener: SqlOpener) : AutoCloseable {
    companion object {
        val REQUIRED = linkedMapOf(
            "message" to setOf("_id", "chat_row_id", "from_me", "key_id", "timestamp"),
            "chat" to setOf("_id", "jid_row_id"),
            "jid" to setOf("_id", "raw_string"),
        )
        val TYPES = mapOf(
            0L to "text", 1L to "image", 2L to "audio", 3L to "video", 4L to "contact", 5L to "location",
            7L to "system", 9L to "document", 10L to "call", 11L to "waiting", 13L to "gif", 14L to "contacts",
            15L to "deleted", 16L to "live_location", 20L to "sticker", 23L to "product", 42L to "view_once_image",
            43L to "view_once_video", 44L to "view_once_audio", 46L to "poll", 64L to "deleted", 66L to "poll",
        )
        val SKIP_TYPES = setOf("system", "call", "waiting")
        val MEDIA_TYPES = setOf("image", "audio", "video", "document", "gif", "sticker",
            "view_once_image", "view_once_video", "view_once_audio")

        /** فهرس على النسخة المفكوكة المؤقتة فقط (لا تُلمس ملفات واتساب) لتسريع الاستعلامات. */
        fun ensureIndex(path: File, opener: SqlOpener) {
            try {
                opener.openReadWrite(path).use { db ->
                    opener.exec(db, "CREATE INDEX IF NOT EXISTS wa_archiver_chat_ts ON message(chat_row_id, timestamp)")
                }
            } catch (_: Exception) {
                // ليس ضرورياً؛ الاستعلامات تعمل بدونه لكن أبطأ
            }
        }
    }

    private val conn: SqlDb
    val tables: Map<String, Set<String>>
    private val lidMap: Map<Long, String>
    private val pushNames: Map<Long, String>
    private var chatCache: List<ChatInfo>? = null

    /** رسائل النسخ التزايدية فوق هذه القاعدة (إن وُجدت) */
    var overlay: IncrementOverlay? = null
        set(v) { field = v; chatCache = null }

    /** @lid (نص) → jid الرقم، من jid_map في القاعدة */
    private val lidRawMap: Map<String, String> by lazy {
        if (!has("jid_map", "lid_row_id", "jid_row_id")) return@lazy emptyMap()
        conn.query("SELECT l.raw_string, j.raw_string FROM jid_map m JOIN jid l ON l._id = m.lid_row_id JOIN jid j ON j._id = m.jid_row_id")
            .filter { truthy(it[0]) && truthy(it[1]) }.associate { it[0].toString() to it[1].toString() }
    }

    /** أسماء push بنص الـ jid */
    private val pushByRaw: Map<String, String> by lazy {
        if (pushNames.isEmpty()) return@lazy emptyMap()
        val ids = pushNames.keys
        val out = HashMap<String, String>()
        for (chunk in ids.chunked(500)) {
            for (r in conn.query("SELECT _id, raw_string FROM jid WHERE _id IN (${chunk.joinToString(",")})")) {
                if (truthy(r[1])) pushNames[r[0].asLong()]?.let { out[r[1].toString()] = it }
            }
        }
        out
    }

    private val baseMaxRow: Long by lazy {
        conn.query("SELECT MAX(_id) FROM message").firstOrNull()?.get(0)?.asLong() ?: 0L
    }

    /** يكمل رسالة من ملف تزايدي: يحوّل @lid للرقم، ويضيف الاسم. */
    private fun fixOverlay(m: Message): Message {
        val raw = m.senderJid
        if (raw.isEmpty() || !raw.endsWith("@lid")) return m
        val pn = lidRawMap[raw] ?: overlay?.pnOfLid?.get(raw)
        val name = m.pushName ?: overlay?.lidNames?.get(raw) ?: pushByRaw[raw]
        return m.copy(senderJid = pn ?: raw, pushName = name)
    }

    init {
        ensureIndex(path, opener)
        try {
            conn = opener.openReadOnly(path)
            tables = loadSchema()
        } catch (e: SchemaError) {
            throw e
        } catch (e: Exception) {
            throw SchemaError("تعذّر فتح قاعدة البيانات المفكوكة: ${e.message}")
        }
        try {
            checkRequired()
        } catch (e: SchemaError) {
            conn.close(); throw e
        }
        lidMap = loadLidMap()
        pushNames = loadPushNames()
    }

    override fun close() = conn.close()

    // ---------- فحص المخطط ----------
    private fun loadSchema(): Map<String, Set<String>> {
        val names = conn.query("SELECT name FROM sqlite_master WHERE type IN ('table','view')").map { it[0] as String }
        return names.associateWith { n ->
            conn.query("PRAGMA table_info(\"$n\")").map { it[1].toString() }.toSet()
        }
    }

    fun has(table: String, vararg cols: String): Boolean =
        tables[table]?.let { t -> cols.all { it in t } } ?: false

    private fun checkRequired() {
        val problems = mutableListOf<String>()
        for ((t, cols) in REQUIRED) {
            val have = tables[t]
            if (have == null) problems.add("الجدول $t غير موجود")
            else {
                val missing = (cols - have).sorted()
                if (missing.isNotEmpty()) problems.add("الجدول $t ينقصه الأعمدة: ${missing.joinToString(", ")}")
            }
        }
        if (problems.isNotEmpty()) {
            val legacy = if ("messages" in tables) " (يبدو أنها نسخة بمخطط واتساب قديم 'messages')" else ""
            throw SchemaError(
                "مخطط قاعدة واتساب غير متوافق مع الأداة" + legacy + ":\n  - " + problems.joinToString("\n  - ") +
                    "\nحدّث واتساب أو التطبيق، وأرسل هذه الرسالة للمطوّر."
            )
        }
    }

    private fun col(table: String, col: String, alias: String, prefix: String): String =
        if (has(table, col)) "$prefix.$col AS $alias" else "NULL AS $alias"

    // ---------- الأسماء ----------
    /** jid_map: يربط معرّف @lid بالرقم الحقيقي (إن وُجد في هذا الإصدار). */
    private fun loadLidMap(): Map<Long, String> {
        if (!has("jid_map", "lid_row_id", "jid_row_id")) return emptyMap()
        val out = HashMap<Long, String>()
        for (r in conn.query("SELECT m.lid_row_id, j.raw_string FROM jid_map m JOIN jid j ON j._id = m.jid_row_id")) {
            val v = r[1]
            if (truthy(v)) out[r[0].asLong()] = v.toString()
        }
        return out
    }

    /** أسماء push (الاسم الذي وضعه الشخص لنفسه). مُفتاحها _id في جدول jid. */
    private fun loadPushNames(): Map<Long, String> {
        val names = HashMap<Long, String>()
        val candidates = listOf(
            Triple("lid_display_name", "lid_row_id", "display_name"),
            Triple("jid_display_name", "jid_row_id", "display_name"),
            Triple("push_name", "jid_row_id", "push_name"),
        )
        for ((table, idcol, namecol) in candidates) {
            if (has(table, idcol, namecol)) {
                for (r in conn.query("SELECT \"$idcol\", \"$namecol\" FROM \"$table\"")) {
                    if (truthy(r[1])) names.putIfAbsent(r[0].asLong(), pyStr(r[1]))
                }
            }
        }
        return names
    }

    fun resolveJid(rowId: Long?, raw: String?): String {
        if (rowId != null && raw != null && raw.isNotEmpty() && raw.endsWith("@lid") && rowId in lidMap) return lidMap.getValue(rowId)
        return raw ?: ""
    }

    /** رقم الهاتف الحقيقي لـ jid (يحوّل @lid عبر jid_map)، أو "" للمجموعات والمعرّفات المجهولة. */
    fun phoneOf(rowId: Long?, jid: String): String {
        val real = if (jid.endsWith("@lid")) resolveJid(rowId, jid) else jid
        return if (real.endsWith("@s.whatsapp.net") || real.endsWith("@c.us"))
            Naming.normalizeNumber(real.substringBefore('@').substringBefore(':')) else ""
    }

    // ---------- المحادثات ----------
    fun listChats(): List<ChatInfo> {
        val subject = col("chat", "subject", "subject", "c")
        val hidden = if (has("chat", "hidden")) "AND COALESCE(c.hidden,0)=0" else ""
        val q = """
            SELECT c._id AS row_id, c.jid_row_id AS jid_row, j.raw_string AS jid, $subject,
                   agg.cnt AS cnt, agg.last_ts AS last_ts
            FROM chat c JOIN jid j ON j._id = c.jid_row_id
            LEFT JOIN (
                SELECT chat_row_id, COUNT(*) AS cnt, MAX(timestamp) AS last_ts
                FROM message GROUP BY chat_row_id
            ) agg ON agg.chat_row_id = c._id
            WHERE j.raw_string IS NOT NULL AND j.raw_string != 'status@broadcast' $hidden
        """
        val out = conn.query(q).map { r ->
            val jid = r["jid"].toString()
            ChatInfo(
                rowId = r["row_id"].asLong(), jid = jid,
                subject = (r["subject"] as? String)?.takeIf { it.isNotEmpty() } ?: "",
                isGroup = jid.endsWith("@g.us"), messageCount = r["cnt"].asLong(),
                lastTimestamp = Math.floorDiv(r["last_ts"].asLong(), 1000L),
                phone = phoneOf(r["jid_row"]?.asLong(), jid),
                pushName = r["jid_row"]?.asLong()?.let { pushNames[it] } ?: "",
            )
        }
        val ov = overlay ?: return out.sortedByDescending { it.lastTimestamp }
        return out.map { c ->
            val extra = ov.forChat(c.jid).filter { it.rowId > baseMaxRow }
            if (extra.isEmpty()) c
            else c.copy(messageCount = c.messageCount + extra.size,
                lastTimestamp = maxOf(c.lastTimestamp, extra.maxOf { it.timestamp }))
        }.sortedByDescending { it.lastTimestamp }
    }

    /** قائمة المحادثات مرة واحدة لكل تشغيل (الاستعلام يمر على جدول الرسائل كله). */
    fun listChatsCached(): List<ChatInfo> = chatCache ?: listChats().also { chatCache = it }

    fun findChat(jid: String): ChatInfo? = listChatsCached().firstOrNull { it.jid == jid }

    // ---------- الرسائل ----------
    private val messageSelect: String by lazy {
        val cols = mutableListOf(
            "m._id AS row_id", "m.key_id AS key_id", "m.timestamp AS ts", "m.from_me AS from_me",
            "m.chat_row_id AS chat_row_id",
            col("message", "text_data", "text", "m"),
            col("message", "message_type", "mtype", "m"),
        )
        val joins = mutableListOf<String>()
        if (has("message", "sender_jid_row_id")) {
            cols += listOf("m.sender_jid_row_id AS sender_row", "sj.raw_string AS sender_raw")
            joins.add("LEFT JOIN jid sj ON sj._id = m.sender_jid_row_id")
        } else {
            cols += listOf("NULL AS sender_row", "NULL AS sender_raw")
        }
        if ("message_media" in tables && has("message_media", "message_row_id")) {
            for ((c, a) in listOf("file_path" to "media_path", "mime_type" to "mime", "media_name" to "media_name",
                "file_size" to "media_size", "media_caption" to "media_caption")) {
                cols.add(col("message_media", c, a, "mm"))
            }
            joins.add("LEFT JOIN message_media mm ON mm.message_row_id = m._id")
        } else {
            cols += listOf("NULL AS media_path", "NULL AS mime", "NULL AS media_name", "NULL AS media_size",
                "NULL AS media_caption")
        }
        if (has("message_quoted", "message_row_id")) {
            cols += listOf(
                col("message_quoted", "text_data", "quoted_text", "mq"),
                col("message_quoted", "key_id", "quoted_id", "mq"),
                col("message_quoted", "message_type", "quoted_type", "mq"),
                "(mq.message_row_id IS NOT NULL) AS is_reply",
            )
            joins.add("LEFT JOIN message_quoted mq ON mq.message_row_id = m._id")
        } else {
            cols += listOf("NULL AS quoted_text", "NULL AS quoted_id", "NULL AS quoted_type", "0 AS is_reply")
        }
        if (has("message_forwarded", "message_row_id")) {
            cols.add("EXISTS(SELECT 1 FROM message_forwarded mf WHERE mf.message_row_id = m._id) AS fwd")
        } else if (has("message", "forwarded")) {
            cols.add("m.forwarded AS fwd")
        } else {
            cols.add("0 AS fwd")
        }
        "SELECT ${cols.joinToString(", ")} FROM message m ${joins.joinToString(" ")}"
    }

    /** رسائل المحادثة التي timestamp >= sinceMs، و(_id > afterRowId أو timestamp >= recentMs). */
    fun messages(chat: ChatInfo, sinceMs: Long, afterRowId: Long = 0, recentMs: Long? = null): List<Message> {
        val where = mutableListOf("m.chat_row_id = ?", "m.timestamp >= ?")
        val params = mutableListOf<Any?>(chat.rowId, sinceMs)
        if (afterRowId != 0L) {
            if (recentMs != null) {
                where.add("(m._id > ? OR m.timestamp >= ?)")
                params.addAll(listOf(afterRowId, recentMs))
            } else {
                where.add("m._id > ?")
                params.add(afterRowId)
            }
        }
        val q = messageSelect + " WHERE " + where.joinToString(" AND ") + " ORDER BY m.timestamp, m._id"
        val base = conn.query(q, params).mapNotNull { toMessage(it, chat) }
        val ov = overlay ?: return base
        // نفس شروط الاستعلام على رسائل الملفات التزايدية؛ عند تكرار _id النسخة الأحدث تغلب
        val extra = ov.forChat(chat.jid).filter { m ->
            val ms = m.timestamp * 1000
            ms >= sinceMs && (afterRowId == 0L || m.rowId > afterRowId || (recentMs != null && ms >= recentMs))
        }
        if (extra.isEmpty()) return base
        val merged = LinkedHashMap<Long, Message>()
        for (m in base) merged[m.rowId] = m
        for (m in extra) merged[m.rowId] = fixOverlay(m).copy(chatJid = chat.jid)
        return merged.values.sortedWith(compareBy({ it.timestamp }, { it.rowId }))
    }

    fun messageByKey(chat: ChatInfo, keyId: String): Message? {
        overlay?.byKey(chat.jid, keyId)?.let { return fixOverlay(it).copy(chatJid = chat.jid) }
        val q = "$messageSelect WHERE m.chat_row_id = ? AND m.key_id = ?"
        val r = conn.query(q, listOf(chat.rowId, keyId)).firstOrNull() ?: return null
        return toMessage(r, chat)
    }

    private fun quotedFallback(chatRowId: Long, keyId: String?): String? {
        if (keyId.isNullOrEmpty() || !has("message", "text_data")) return null
        val r = conn.query("SELECT text_data FROM message WHERE chat_row_id = ? AND key_id = ?", listOf(chatRowId, keyId))
            .firstOrNull() ?: return null
        return r[0] as? String
    }

    private fun str(v: Any?): String? = v?.let { pyStr(it) }

    private fun toMessage(r: Row, chat: ChatInfo): Message? {
        val mtypeNum = r["mtype"]
        val mtype = if (mtypeNum != null) TYPES[mtypeNum.asLong()] ?: "type_${pyStr(mtypeNum)}" else "text"
        if (mtype in SKIP_TYPES) return null
        if (!truthy(r["key_id"]) || r["ts"] == null) return null
        val fromMe = truthy(r["from_me"])
        val senderRow = r["sender_row"]?.asLong()
        val senderRaw = str(r["sender_raw"])
        val sender = when {
            fromMe -> ""
            !senderRaw.isNullOrEmpty() -> resolveJid(senderRow, senderRaw)
            else -> chat.jid // محادثة خاصة: المرسل هو صاحب المحادثة
        }
        val text = str(r["text"])?.takeIf { it.isNotEmpty() } ?: str(r["media_caption"])?.takeIf { it.isNotEmpty() } ?: ""
        var quotedText: String? = null
        var quotedId: String? = null
        if (truthy(r["is_reply"])) {
            quotedId = str(r["quoted_id"])
            quotedText = str(r["quoted_text"])?.takeIf { it.isNotEmpty() } ?: quotedFallback(r["chat_row_id"].asLong(), quotedId)
            if (quotedText.isNullOrEmpty() && r["quoted_type"] != null) {
                quotedText = "[${TYPES[r["quoted_type"].asLong()] ?: "وسيط"}]"
            }
            quotedText = quotedText?.takeIf { it.isNotEmpty() } ?: ""
        }
        val mediaPath = str(r["media_path"])
        val mime = str(r["mime"])
        val hasMedia = mtype in MEDIA_TYPES || !mediaPath.isNullOrEmpty() || !mime.isNullOrEmpty()
        return Message(
            rowId = r["row_id"].asLong(), keyId = pyStr(r["key_id"]), chatJid = chat.jid,
            timestamp = Math.floorDiv(r["ts"].asLong(), 1000L), fromMe = fromMe, senderJid = sender, type = mtype,
            text = if (mtype == "deleted" && text.isEmpty()) "[رسالة محذوفة]" else text,
            mediaPath = if (hasMedia) mediaPath else null,
            mime = if (hasMedia) mime else null,
            mediaName = if (hasMedia) str(r["media_name"]) else null,
            mediaSize = if (hasMedia) r["media_size"]?.asLong() else null,
            quotedText = quotedText, quotedId = quotedId, forwarded = truthy(r["fwd"]),
            pushName = if (senderRow != null && senderRow != 0L) pushNames[senderRow] else null,
            hasMedia = hasMedia,
        )
    }
}
