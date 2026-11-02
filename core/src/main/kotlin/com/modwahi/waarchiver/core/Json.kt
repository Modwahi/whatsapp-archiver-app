package com.modwahi.waarchiver.core

import java.math.BigDecimal

/**
 * JSON بسيط يحافظ على ترتيب المفاتيح، ويكتب بنفس صيغة Python:
 * json.dumps(obj, ensure_ascii=False, indent=2)
 *
 * القيم: null, Boolean, Long, Double, String, List<Any?>, Map<String, Any?> (LinkedHashMap).
 */
typealias JObj = LinkedHashMap<String, Any?>

object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("JSON: بيانات زائدة عند ${p.i}")
        return v
    }

    /** مطابق لـ json.dumps(obj, ensure_ascii=False, indent=2) */
    fun dumps(v: Any?): String = StringBuilder().also { write(it, v, 0) }.toString()

    private fun write(sb: StringBuilder, v: Any?, level: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> sb.append(pyFloat(v))
            is Float -> sb.append(pyFloat(v.toDouble()))
            is String -> str(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    indent(sb, level + 1)
                    str(sb, k.toString())
                    sb.append(": ")
                    write(sb, value, level + 1)
                }
                sb.append('\n')
                indent(sb, level)
                sb.append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                v.forEachIndexed { idx, value ->
                    if (idx > 0) sb.append(",\n")
                    indent(sb, level + 1)
                    write(sb, value, level + 1)
                }
                sb.append('\n')
                indent(sb, level)
                sb.append(']')
            }
            else -> throw IllegalArgumentException("JSON: نوع غير مدعوم ${v::class.java}")
        }
    }

    private fun indent(sb: StringBuilder, level: Int) {
        repeat(level * 2) { sb.append(' ') }
    }

    /** مثل encoder في Python مع ensure_ascii=False */
    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** repr(float) في Python (للأرقام العادية) */
    fun pyFloat(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        val abs = Math.abs(d)
        if (abs != 0.0 && (abs < 1e-4 || abs >= 1e16)) {
            // صيغة علمية مثل Python: 1e+16, 1.5e-05
            val s = d.toString() // مثل 1.0E16
            val (m, e) = s.split('E')
            val mant = m.removeSuffix(".0")
            val exp = e.toInt()
            return mant + "e" + (if (exp < 0) "-" else "+") + String.format(java.util.Locale.ROOT, "%02d", Math.abs(exp))
        }
        val plain = BigDecimal(d.toString()).toPlainString()
        return if (plain.contains('.')) plain else "$plain.0"
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw IllegalArgumentException("JSON: نهاية غير متوقعة")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("JSON: حرف غير متوقع '$c' عند $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("JSON: غير صالح عند $i")
            i += word.length
            return v
        }

        fun obj(): JObj {
            val m = JObj()
            i++
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = string()
                ws()
                if (s[i] != ':') throw IllegalArgumentException("JSON: متوقع ':' عند $i")
                i++
                ws()
                m[k] = value()
                ws()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("JSON: متوقع ',' أو '}' عند $i")
                }
            }
        }

        fun arr(): MutableList<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value())
                ws()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IllegalArgumentException("JSON: متوقع ',' أو ']' عند $i")
                }
            }
        }

        fun string(): String {
            if (s[i] != '"') throw IllegalArgumentException("JSON: متوقع نص عند $i")
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw IllegalArgumentException("JSON: هروب غير صالح \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isFloat = false
            if (i < s.length && s[i] == '.') { isFloat = true; i++; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isFloat = true; i++
                if (s[i] == '+' || s[i] == '-') i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val t = s.substring(start, i)
            return if (isFloat) t.toDouble() else t.toLong()
        }
    }
}

// مساعدات قراءة القيم من خرائط JSON بطريقة Python (truthiness)
internal fun truthy(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Long -> v != 0L
    is Int -> v != 0
    is Double -> v != 0.0
    is String -> v.isNotEmpty()
    is Collection<*> -> v.isNotEmpty()
    is Map<*, *> -> v.isNotEmpty()
    else -> true
}

internal fun Any?.asLong(default: Long = 0): Long = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is Double -> this.toLong()
    is Boolean -> if (this) 1 else 0
    is String -> this.toLongOrNull() ?: default
    else -> default
}

internal fun Any?.asDouble(default: Double = 0.0): Double = when (this) {
    is Number -> this.toDouble()
    else -> default
}

internal fun pyStr(v: Any?): String = when (v) {
    null -> "None"
    is Boolean -> if (v) "True" else "False"
    is Double -> Json.pyFloat(v)
    else -> v.toString()
}
