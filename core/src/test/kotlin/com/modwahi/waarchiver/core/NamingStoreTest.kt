package com.modwahi.waarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** منقولة من tests/test_naming_store.py و test_security.py */
class NamingStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val aden = ZoneId.of("Asia/Aden")
    private fun ts(s: String) = LocalDateTime.parse(s).atZone(aden).toEpochSecond()

    @Test fun sanitizeWindowsCharsAndArabic() {
        assertEquals("تحويلات_ 2026_11 _عاجل__", Naming.sanitizeName("تحويلات: 2026/11 *عاجل*?"))
        assertEquals("a_b_c_d_e_f", Naming.sanitizeName("a\\b\"c<d>e|f"))
        assertEquals("مجموعة أولاد أبو حسان", Naming.sanitizeName("  مجموعة   أولاد أبو حسان ..  "))
        assertEquals("مالية", Naming.sanitizeName("‫مالية‬\n"))
        assertEquals("بدون_اسم", Naming.sanitizeName(""))
        assertEquals("بدون_اسم", Naming.sanitizeName("???"))
        assertEquals("_CON", Naming.sanitizeName("CON"))
        assertTrue(Naming.sanitizeName("ا".repeat(300)).length <= 80)
    }

    @Test fun datesByMessageTime() {
        val t = ts("2026-10-30T23:59:58")
        assertEquals("2026-10-30", Naming.dateKey(t, aden))
        assertEquals("23:59:58", Naming.timeOfDay(t, aden))
        assertEquals("2026-10-31", Naming.dateKey(t + 3, aden))
    }

    @Test fun mediaNames() {
        val t = ts("2026-10-31T09:05:07")
        assertEquals("090507_محمد_المحاسب_EF123456.jpg",
            Naming.mediaFileName(t, "محمد: \"المحاسب\"", "3EB0ABCDEF123456", "image/jpeg", aden))
        assertEquals("090507_مجهول_x.pdf", Naming.mediaFileName(t, "", "x", null, aden, "كشف.PDF"))
        assertEquals("opus", Naming.extensionFor("audio/ogg; codecs=opus"))
        assertEquals("xlsx", Naming.extensionFor("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertEquals("bin", Naming.extensionFor(null))
        assertEquals("noid", Naming.shortId(""))
        assertEquals("", Naming.splitExt(".bashrc"))
        assertEquals(".pdf", Naming.splitExt("Media/كشف: حساب.pdf"))
    }

    @Test fun txtLineAllParts() {
        val rec = JObj().apply {
            put("timestamp", ts("2026-10-31T10:00:00")); put("sender", "أحمد (967771111111)"); put("text", "حوّل\nالمبلغ")
            put("type", "image"); put("has_media", true); put("media_file", "100000_أحمد_X.jpg"); put("quoted_text", "س".repeat(60))
            put("forwarded", true)
        }
        assertEquals("10:00:00 | أحمد (967771111111) | حوّل ↵ المبلغ | 100000_أحمد_X.jpg | رد على: ${"س".repeat(50)}… | معاد توجيهها",
            Naming.formatTxtLine(rec, aden))
        val rec2 = JObj().apply {
            put("timestamp", rec["timestamp"]); put("sender", "أنا"); put("text", ""); put("type", "image")
            put("has_media", true); put("media_status", "not_downloaded"); put("quoted_text", null)
        }
        assertEquals("10:00:00 | أنا | [صورة] | [وسيط غير منزّل]", Naming.formatTxtLine(rec2, aden))
        assertEquals("قصير", Naming.snippet("قصير"))
        val rec3 = JObj().apply { put("timestamp", rec["timestamp"]); put("sender", "x"); put("type", "type_99") }
        assertEquals("10:00:00 | x | [نوع غير معروف 99]", Naming.formatTxtLine(rec3, aden))
    }

    private fun r(id: String, row: Long, t: Long) = JObj().apply {
        put("id", id); put("row_id", row); put("timestamp", t); put("sender", "x"); put("text", row.toString())
    }

    @Test fun dayfileMergeNoDuplicates() {
        val dir = tmp.root
        val d = DayFile()
        assertEquals(2, d.add(listOf(r("A", 1, 10), r("B", 2, 5))))
        d.write(dir, aden)
        val d2 = DayFile.fromJsonBytes(File(dir, JSON_NAME).readBytes())
        assertEquals(1, d2.add(listOf(r("A", 1, 10), r("C", 3, 20))))
        d2.write(dir, aden)
        @Suppress("UNCHECKED_CAST")
        val recs = Json.parse(File(dir, JSON_NAME).readText()) as List<JObj>
        assertEquals(listOf("B", "A", "C"), recs.map { it["id"] })
        val txt = File(dir, TXT_NAME).readBytes()
        assertTrue(String(txt, Charsets.UTF_8).startsWith("﻿"))
        assertEquals(3, String(txt, Charsets.UTF_8).split("\r\n").size - 1)
    }

    @Test fun jsonMatchesPythonDumps() {
        val v = JObj().apply {
            put("a", "سطر\n\"اقتباس\" \\ \t \u0001 😀"); put("n", null); put("b", false); put("l", listOf<Any?>())
            put("o", JObj()); put("x", listOf(1L, 2.5, JObj().apply { put("k", 1L) }))
        }
        val expected = "{\n  \"a\": \"سطر\\n\\\"اقتباس\\\" \\\\ \\t \\u0001 😀\",\n  \"n\": null,\n  \"b\": false,\n" +
            "  \"l\": [],\n  \"o\": {},\n  \"x\": [\n    1,\n    2.5,\n    {\n      \"k\": 1\n    }\n  ]\n}"
        assertEquals(expected, Json.dumps(v))
        assertEquals(expected, Json.dumps(Json.parse(expected)))
        assertEquals("1793574000.0", Json.pyFloat(1793574000.0))
    }

    @Test fun pythonFormatting() {
        assertEquals("2.0", pyFixed(2.0, 1))
        assertEquals("40", pyFixed(40.0, 0))
        assertEquals("0", pyFixed(0.5, 0)) // half-even مثل Python
        assertEquals("2", pyFixed(1.5, 0))
    }

    @Test fun keyRedactedInLogs() {
        val key = "a3f1".repeat(16)
        val log = FileLog(tmp.root)
        log.info("اختبار تسريب $key")
        val all = tmp.root.walkTopDown().filter { it.isFile }.joinToString { it.readText() }
        assertFalse(all.contains(key))
        assertTrue(all.contains("<مفتاح-محجوب>"))
    }
}
