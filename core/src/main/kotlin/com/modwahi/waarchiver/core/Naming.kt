package com.modwahi.waarchiver.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** تنظيف الأسماء، التواريخ، أسماء ملفات الوسائط، وصياغة سطر الرسائل.txt — منقول من naming.py */
object Naming {
    private val INVISIBLE = Regex("[‎‏‪-‮⁦-⁩﻿]")
    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|\\x00-\\x1f\\x7f]")
    private val RESERVED = Regex("^(con|prn|aux|nul|com[0-9]|lpt[0-9])$", RegexOption.IGNORE_CASE)
    private val WS = Regex("[\\s\\u001c-\\u001f\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+")

    /** str.isspace() في Python */
    fun pyIsSpace(c: Int): Boolean = c in 0x09..0x0d || c in 0x1c..0x20 || c == 0x85 || c == 0xa0 ||
        c == 0x1680 || c in 0x2000..0x200a || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f || c == 0x3000

    fun pyStrip(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && pyIsSpace(s.codePointAt(start))) start += Character.charCount(s.codePointAt(start))
        while (end > start && pyIsSpace(s.codePointBefore(end))) end -= Character.charCount(s.codePointBefore(end))
        return s.substring(start, end)
    }

    /** s[:n] في Python (بعدد الحروف code points لا UTF-16) */
    fun pyTake(s: String, n: Int): String {
        if (s.codePointCount(0, s.length) <= n) return s
        return s.substring(0, s.offsetByCodePoints(0, n))
    }

    fun pyLen(s: String): Int = s.codePointCount(0, s.length)

    /** اسم صالح لملف/مجلد في ويندوز وGoogle Drive، مع الحفاظ على العربي. */
    fun sanitizeName(name: Any?, fallback: String = "بدون_اسم", maxLen: Int = 80): String {
        var s = INVISIBLE.replace(name?.toString() ?: "", "")
        s = s.replace(Regex("[\t\r\n]"), " ")
        s = FORBIDDEN.replace(s, "_")
        s = pyStrip(WS.replace(s, " "))
        if (pyLen(s) > maxLen) s = pyStrip(pyTake(s, maxLen))
        s = s.trimEnd('.', ' ')
        if (s.isEmpty() || s.all { it == '_' }) s = fallback
        if (RESERVED.matches(s.split(".")[0])) s = "_$s"
        return s
    }

    fun localDt(ts: Long, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochSecond(ts), zone)

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun dateKey(ts: Long, zone: ZoneId): String = localDt(ts, zone).format(DATE)
    fun timeOfDay(ts: Long, zone: ZoneId): String = localDt(ts, zone).format(TIME)
    /** datetime.fromtimestamp(ts).isoformat() — بدون إزاحة التوقيت */
    fun isoLocal(ts: Long, zone: ZoneId): String = localDt(ts, zone).format(ISO)

    val MIME_EXT = mapOf(
        "image/jpeg" to "jpg", "image/jpg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif",
        "image/heic" to "heic", "video/mp4" to "mp4", "video/3gpp" to "3gp", "video/quicktime" to "mov",
        "audio/ogg" to "opus", "audio/opus" to "opus", "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/aac" to "aac",
        "audio/amr" to "amr", "application/pdf" to "pdf", "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/zip" to "zip", "application/x-rar-compressed" to "rar", "application/vnd.rar" to "rar",
        "text/plain" to "txt", "text/csv" to "csv",
    )

    private val EXT_RE = Regex("[a-z0-9]{1,8}")

    /** os.path.splitext(p)[1] */
    fun splitExt(p: String): String {
        val sep = p.lastIndexOf('/')
        val dot = p.lastIndexOf('.')
        if (dot > sep) {
            var i = sep + 1
            while (i < dot) {
                if (p[i] != '.') return p.substring(dot)
                i++
            }
        }
        return ""
    }

    fun extensionFor(mime: String?, vararg names: String?): String {
        for (n in names) {
            if (!n.isNullOrEmpty()) {
                val ext = splitExt(n).removePrefix(".").lowercase()
                if (EXT_RE.matches(ext)) return ext
            }
        }
        val m = pyStrip((mime ?: "").split(";")[0]).lowercase()
        MIME_EXT[m]?.let { return it }
        val sub = if ("/" in m) m.split("/").last() else ""
        return if (EXT_RE.matches(sub)) sub else "bin"
    }

    fun shortId(keyId: String?): String {
        val clean = (keyId ?: "").replace(Regex("[^A-Za-z0-9]"), "")
        return clean.takeLast(8).ifEmpty { "noid" }
    }

    /** HHMMSS_<المرسل>_<id قصير>.<امتداد> */
    fun mediaFileName(ts: Long, sender: String?, keyId: String, mime: String?, zone: ZoneId, vararg names: String?): String {
        var who = sanitizeName(sender, fallback = "مجهول", maxLen = 40)
        who = who.replace(Regex("[ _]+"), "_").trim('_').ifEmpty { "مجهول" }
        return "${timeOfDay(ts, zone).replace(":", "")}_${who}_${shortId(keyId)}.${extensionFor(mime, *names)}"
    }

    fun oneLine(s: Any?): String {
        val str = if (truthy(s)) pyStr(s) else ""
        return pyStrip(str.replace(Regex("\r?\n"), " ↵ "))
    }

    fun snippet(s: Any?, n: Int = 50): String {
        val t = oneLine(s)
        return if (pyLen(t) > n) pyTake(t, n) + "…" else t
    }

    val TYPE_LABEL = mapOf(
        "image" to "[صورة]", "video" to "[فيديو]", "audio" to "[صوت]", "document" to "[مستند]", "sticker" to "[ملصق]",
        "gif" to "[صورة متحركة]", "contact" to "[جهة اتصال]", "contacts" to "[جهات اتصال]", "location" to "[موقع]",
        "live_location" to "[موقع مباشر]", "poll" to "[استطلاع]", "view_once_image" to "[صورة لمرة واحدة]",
        "view_once_video" to "[فيديو لمرة واحدة]", "view_once_audio" to "[صوت لمرة واحدة]",
    )

    val MEDIA_STATUS_LABEL = mapOf(
        "not_downloaded" to "[وسيط غير منزّل]",
        "missing" to "[وسيط محذوف أو غير متاح]",
        "too_large" to "[وسيط أكبر من الحد]",
        "error" to "[تعذّر نسخ الوسيط]",
    )

    /** الوقت | المرسل | النص | ملف الوسيط | رد على: ... | معاد توجيهها */
    fun formatTxtLine(rec: Map<String, Any?>, zone: ZoneId): String {
        val parts = mutableListOf(
            timeOfDay(rec["timestamp"].asLong(), zone),
            (rec["sender"] as? String)?.takeIf { it.isNotEmpty() } ?: "",
            oneLine(rec["text"]),
        )
        if (parts[2].isEmpty()) {
            val t = (rec["type"] as? String) ?: ""
            parts[2] = TYPE_LABEL[t] ?: if (t.startsWith("type_")) "[نوع غير معروف ${t.substring(5)}]" else ""
        }
        if (truthy(rec["has_media"])) {
            val mf = rec["media_file"]
            parts.add(if (truthy(mf)) mf.toString()
                      else MEDIA_STATUS_LABEL[rec["media_status"] as? String] ?: "[وسيط غير منزّل]")
        }
        if (rec["quoted_text"] != null) parts.add("رد على: ${snippet(rec["quoted_text"])}")
        if (truthy(rec["forwarded"])) parts.add("معاد توجيهها")
        return parts.joinToString(" | ")
    }

    fun normalizeNumber(s: String?): String {
        // مثل \d في Python: أي رقم يونيكود (بدون regex: ICU في أندرويد لا يدعم علم Unicode الخاص بـ Java)
        var digits = (s ?: "").filter { Character.isDigit(it) }
        if (digits.startsWith("00")) digits = digits.substring(2)
        return digits
    }
}
