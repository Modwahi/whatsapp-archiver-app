package com.modwahi.waarchiver.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * يستنتج بنية protobuf بدون مخطط: لكل مسار حقل (مثل 2.1.5) نوعه وعدد مرات ظهوره ونطاق قيمه الرقمية.
 * النصوص لا تُعرض أبداً: فقط تصنيفها (معرّف جهة / hex / مسار / نص) وأطوالها.
 */
class ProtoSchema {
    private class Stat {
        var count = 0L
        val wires = HashSet<Int>()
        var vMin = Long.MAX_VALUE; var vMax = Long.MIN_VALUE
        val small = HashSet<Long>()
        var lenMin = Int.MAX_VALUE; var lenMax = 0
        val kinds = HashMap<String, Int>()
        val servers = HashSet<String>()
    }

    private val stats = java.util.TreeMap<String, Stat>(compareBy<String>({ it.split('.').size }, { it }))
    var records = 0; private set
    var trailing = 0; private set

    /** ملف من سجلات مسبوقة بطولها (varint) */
    fun addDelimited(b: ByteArray) {
        var p = 0
        while (p < b.size) {
            val (len, q) = varint(b, p) ?: break
            if (len < 0 || q + len > b.size) break
            records++
            parse(b, q, q + len.toInt(), "", 0)
            p = q + len.toInt()
        }
        trailing = b.size - p
    }

    private fun varint(b: ByteArray, start: Int): Pair<Long, Int>? {
        var r = 0L; var s = 0; var p = start
        while (p < b.size && s < 64) {
            val x = b[p++].toInt() and 0xff
            r = r or ((x and 0x7f).toLong() shl s)
            if (x and 0x80 == 0) return r to p
            s += 7
        }
        return null
    }

    /** يتحقق أن المقطع رسالة protobuf صالحة بالكامل */
    private fun isMessage(b: ByteArray, from: Int, to: Int, depth: Int): Boolean {
        if (to <= from || depth > 12) return false
        var p = from
        while (p < to) {
            val (tag, q) = varint(b, p) ?: return false
            val field = tag ushr 3; val wire = (tag and 7).toInt()
            if (field < 1 || field > 10000) return false
            p = q
            when (wire) {
                0 -> p = (varint(b, p) ?: return false).second
                1 -> p += 8
                5 -> p += 4
                2 -> { val (l, r) = varint(b, p) ?: return false; if (l < 0 || r + l > to) return false; p = r + l.toInt() }
                else -> return false
            }
            if (p > to) return false
        }
        return p == to
    }

    private fun stat(path: String) = stats.getOrPut(path) { Stat() }

    private fun parse(b: ByteArray, from: Int, to: Int, prefix: String, depth: Int) {
        var p = from
        while (p < to) {
            val (tag, q) = varint(b, p) ?: return
            val field = tag ushr 3; val wire = (tag and 7).toInt()
            val path = if (prefix.isEmpty()) "$field" else "$prefix.$field"
            val s = stat(path); s.count++; s.wires.add(wire)
            p = q
            when (wire) {
                0 -> { val (v, r) = varint(b, p) ?: return; p = r
                    s.vMin = minOf(s.vMin, v); s.vMax = maxOf(s.vMax, v); if (s.small.size <= 12) s.small.add(v) }
                1 -> p += 8
                5 -> p += 4
                2 -> {
                    val (l, r) = varint(b, p) ?: return
                    val len = l.toInt(); val end = r + len
                    if (len < 0 || end > to) return
                    s.lenMin = minOf(s.lenMin, len); s.lenMax = maxOf(s.lenMax, len)
                    val kind = classify(b, r, end, depth, s)
                    s.kinds.merge(kind, 1, Int::plus)
                    if (kind == "رسالة") parse(b, r, end, path, depth + 1)
                    p = end
                }
                else -> return
            }
        }
    }

    private val JID = Regex("""^[0-9:\-.]+@([a-z.]+)$""")
    private val HEX = Regex("""^[0-9A-Fa-f]{12,}$""")

    private fun classify(b: ByteArray, from: Int, to: Int, depth: Int, s: Stat): String {
        if (to == from) return "فارغ"
        val str = try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b, from, to - from)).toString()
        } catch (e: Exception) { null }
        val printable = str != null && str.all { it == '\n' || it == '\t' || it == '\r' || !it.isISOControl() }
        if (printable && str != null) {
            JID.find(str)?.let { s.servers.add(it.groupValues[1]); return "معرّف جهة" }
            if (HEX.matches(str)) return "hex"
            if (str.contains('/') && !str.contains(' ') && str.contains('.')) return "مسار/رابط"
            if (str.any { it in '؀'..'ۿ' } || str.contains(' ')) return "نص"
            // كلمة قصيرة قد تكون رسالة protobuf صغيرة بالصدفة: نفضّل النص إلا إن كانت رسالة واضحة
            if (!isMessage(b, from, to, depth)) return "نص"
        }
        if (isMessage(b, from, to, depth)) return "رسالة"
        return "ثنائي"
    }

    fun report(ind: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val out = StringBuilder("${ind}سجلات: $records" + (if (trailing > 0) " — بايتات زائدة: $trailing" else "") + "\n")
        for ((path, s) in stats.entries.take(400)) {
            val parts = ArrayList<String>()
            parts.add("×${s.count}")
            if (0 in s.wires) {
                var r = "رقم ${s.vMin}..${s.vMax}"
                if (s.vMin > 1_400_000_000_000L && s.vMax < 2_100_000_000_000L) r += " (وقت ms: ${fmt.format(Date(s.vMin))} → ${fmt.format(Date(s.vMax))})"
                else if (s.vMin > 1_400_000_000L && s.vMax < 2_100_000_000L) r += " (وقت s: ${fmt.format(Date(s.vMin * 1000))} → ${fmt.format(Date(s.vMax * 1000))})"
                else if (s.small.size <= 12) r += " {${s.small.sorted().joinToString(",")}}"
                parts.add(r)
            }
            if (2 in s.wires) {
                parts.add(s.kinds.entries.sortedByDescending { it.value }.joinToString("/") { "${it.key}:${it.value}" } +
                    " طول ${s.lenMin}..${s.lenMax}")
                if (s.servers.isNotEmpty()) parts.add("@" + s.servers.sorted().joinToString(",@"))
            }
            if (1 in s.wires) parts.add("fixed64")
            if (5 in s.wires) parts.add("fixed32")
            out.append(ind).append(path).append(": ").append(parts.joinToString(" — ")).append('\n')
        }
        if (stats.size > 400) out.append(ind).append("… ${stats.size - 400} مساراً أخرى\n")
        return out.toString()
    }
}
