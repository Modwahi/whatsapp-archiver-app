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
                when {
                    String(head, Charsets.ISO_8859_1).startsWith("SQLite format 3") ->
                        opener.openReadOnly(plain).use { db -> describe(db, out) }
                    head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> describeZip(plain, tmp, opener, out)
                    else -> out.appendLine("صيغة غير معروفة")
                }
            } catch (e: Exception) {
                out.appendLine("خطأ: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                for (s in listOf("", "-journal", "-wal", "-shm")) File(plain.path + s).delete()
            }
        }
        return ArchLog.redact(out.toString())
    }

    private val JSON_KEY = Regex("\"([A-Za-z_][A-Za-z0-9_.]{0,40})\"\\s*:")

    /** يسرد محتوى ZIP: الأسماء والأحجام ونوع كل ملف؛ قواعد SQLite تُوصف، و JSON تُعرض مفاتيحه فقط. */
    internal fun describeZip(file: File, tmp: File, opener: SqlOpener, out: StringBuilder) {
        out.appendLine("ملف ZIP:")
        java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zip ->
            var count = 0
            while (true) {
                val e = zip.nextEntry ?: break
                count++
                if (count > 60) { out.appendLine("… (المزيد)"); break }
                if (e.isDirectory) { out.appendLine("  [مجلد] ${e.name}"); continue }
                val inner = File(tmp, "probe-entry.bin")
                try {
                    val n = inner.outputStream().use { zip.copyTo(it) }
                    val bytes = inner.inputStream().use { s -> ByteArray(minOf(n, 64L).toInt()).also { s.read(it) } }
                    val hex = bytes.take(8).joinToString("") { "%02x".format(it) }
                    val text = bytes.all { it >= 9 && it != 127.toByte() }
                    val kind = when {
                        String(bytes, Charsets.ISO_8859_1).startsWith("SQLite format 3") -> "SQLite"
                        bytes.isNotEmpty() && (bytes[0] == '{'.code.toByte() || bytes[0] == '['.code.toByte()) -> "JSON"
                        text -> "نص"
                        else -> "ثنائي"
                    }
                    out.appendLine("  - ${e.name} — $n بايت — $kind — $hex")
                    when (kind) {
                        "SQLite" -> opener.openReadOnly(inner).use { db -> describe(db, out, "      ") }
                        "JSON", "نص" -> {
                            val all = inner.readText(Charsets.UTF_8)
                            val keys = JSON_KEY.findAll(all).map { it.groupValues[1] }.distinct().take(80).toList()
                            if (keys.isNotEmpty()) out.appendLine("      مفاتيح: ${keys.joinToString(", ")}")
                            out.appendLine("      أسطر: ${all.count { it == '\n' } + 1}")
                        }
                    }
                } finally {
                    for (x in listOf("", "-journal", "-wal", "-shm")) File(inner.path + x).delete()
                }
            }
            if (count == 0) out.appendLine("  (فارغ)")
        }
    }

    private fun describe(db: SqlDb, out: StringBuilder, ind: String = "") {
        val objs = db.query("SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")
        val tables = objs.filter { it["type"] == "table" }.map { it["name"] as String }
        val others = objs.groupBy({ it["type"] as String }, { it["name"] as String }).filterKeys { it != "table" }
        out.appendLine(ind + "جداول: ${tables.size}" + others.entries.joinToString("") { " — ${it.key}: ${it.value.size}" })
        val empty = ArrayList<String>()
        for (t in tables) {
            val count = runCatching { (db.query("SELECT count(*) AS c FROM ${q(t)}").first()["c"] as Number).toLong() }
                .getOrElse { out.appendLine(ind + "- $t: تعذّر العد (${it.message})"); continue }
            if (count == 0L) { empty.add(t); continue }
            val cols = db.query("PRAGMA table_info(${q(t)})").map { it["name"] as String }
            out.appendLine(ind + "- $t: $count صف")
            out.appendLine(ind + "    أعمدة: ${cols.joinToString(", ")}")
            for (c in RANGE_COLS.filter { it in cols }) {
                runCatching {
                    val r = db.query("SELECT min(${q(c)}) AS a, max(${q(c)}) AS b FROM ${q(t)}").first()
                    val a = r["a"]; val b = r["b"]
                    val show = { v: Any? ->
                        if (c.contains("timestamp") && v is Number && v.toLong() > 1_000_000_000_000L) "$v (${time(v.toLong() / 1000.0)})" else "$v"
                    }
                    out.appendLine(ind + "    $c: ${show(a)} .. ${show(b)}")
                }
            }
        }
        if (empty.isNotEmpty()) out.appendLine(ind + "جداول فارغة (${empty.size}): ${empty.joinToString(", ")}")
    }
}
