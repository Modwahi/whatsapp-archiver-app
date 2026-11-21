package com.modwahi.waarchiver.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * تشخيص ملفات النسخ التزايدية (msgstore-increment-N.db.crypt15):
 * يفكها مؤقتاً ويصف بنيتها فقط — أسماء الجداول والأعمدة وعدد الصفوف وأرقام المعرّفات والأوقات.
 * لا يكتب أي نص رسالة أو رقم هاتف أو اسم. الملف المفكوك يُحذف دائماً.
 */
object IncrementProbe {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private fun time(s: Double) = fmt.format(Date((s * 1000).toLong()))
    private fun q(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    /** أعمدة رقمية آمنة نعرض أصغر/أكبر قيمة لها إن وُجدت */
    private val RANGE_COLS = listOf("_id", "chat_row_id", "timestamp", "received_timestamp", "sort_id",
        "message_row_id", "jid_row_id", "sender_jid_row_id", "message_type", "from_me")

    fun report(base: SourceFile?, files: List<SourceFile>, key: String, tmp: File, opener: SqlOpener): String {
        val out = StringBuilder()
        out.appendLine("== تشخيص النسخ التزايدية ==")
        if (base != null) out.appendLine("النسخة الكاملة: ${base.name} — ${base.size} بايت — ${time(base.mtime)}")
        if (files.isEmpty()) out.appendLine("لا توجد ملفات msgstore-increment في Databases")
        tmp.mkdirs()
        for (f in files) {
            out.appendLine()
            out.appendLine("## ${f.name} — ${f.size} بايت — ${time(f.mtime)}")
            val plain = File(tmp, "probe.bin")
            try {
                val n = try {
                    f.open().use { Crypt15.decrypt(it, f.size, plain, key, expectSqlite = false) }
                } catch (e: DecryptError) {
                    out.appendLine("فشل الفك: ${e.message}"); continue
                }
                val head = plain.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
                out.appendLine("حجم بعد الفك: $n بايت — أول 16 بايت: ${head.joinToString("") { "%02x".format(it) }}")
                if (!String(head, Charsets.ISO_8859_1).startsWith("SQLite format 3")) {
                    out.appendLine("ليس قاعدة SQLite"); continue
                }
                opener.openReadOnly(plain).use { db -> describe(db, out) }
            } catch (e: Exception) {
                out.appendLine("خطأ: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                for (s in listOf("", "-journal", "-wal", "-shm")) File(plain.path + s).delete()
            }
        }
        return ArchLog.redact(out.toString())
    }

    private fun describe(db: SqlDb, out: StringBuilder) {
        val objs = db.query("SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")
        val tables = objs.filter { it["type"] == "table" }.map { it["name"] as String }
        val others = objs.groupBy({ it["type"] as String }, { it["name"] as String }).filterKeys { it != "table" }
        out.appendLine("جداول: ${tables.size}" + others.entries.joinToString("") { " — ${it.key}: ${it.value.size}" })
        val empty = ArrayList<String>()
        for (t in tables) {
            val count = runCatching { (db.query("SELECT count(*) AS c FROM ${q(t)}").first()["c"] as Number).toLong() }
                .getOrElse { out.appendLine("- $t: تعذّر العد (${it.message})"); continue }
            if (count == 0L) { empty.add(t); continue }
            val cols = db.query("PRAGMA table_info(${q(t)})").map { it["name"] as String }
            out.appendLine("- $t: $count صف")
            out.appendLine("    أعمدة: ${cols.joinToString(", ")}")
            for (c in RANGE_COLS.filter { it in cols }) {
                runCatching {
                    val r = db.query("SELECT min(${q(c)}) AS a, max(${q(c)}) AS b FROM ${q(t)}").first()
                    val a = r["a"]; val b = r["b"]
                    val show = { v: Any? ->
                        if (c.contains("timestamp") && v is Number && v.toLong() > 1_000_000_000_000L) "$v (${time(v.toLong() / 1000.0)})" else "$v"
                    }
                    out.appendLine("    $c: ${show(a)} .. ${show(b)}")
                }
            }
        }
        if (empty.isNotEmpty()) out.appendLine("جداول فارغة (${empty.size}): ${empty.joinToString(", ")}")
    }
}
