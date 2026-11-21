package com.modwahi.waarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/** messages.bin مبني يدوياً بنفس بنية HistorySync/WebMessageInfo التي رأيناها في نسخ حقيقية. */
class IncrementsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = File("src/test/resources/fixtures/key.txt").readText().trim()

    private class W : ByteArrayOutputStream() {
        fun varint(v: Long) { var x = v; while (true) { if (x < 0x80) { write(x.toInt()); return }; write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 } }
        fun num(f: Int, v: Long) { varint((f shl 3).toLong()); varint(v) }
        fun bytes(f: Int, b: ByteArray) { varint(((f shl 3) or 2).toLong()); varint(b.size.toLong()); write(b) }
        fun str(f: Int, s: String) = bytes(f, s.toByteArray())
        fun msg(f: Int, block: W.() -> Unit) = bytes(f, W().apply(block).toByteArray())
    }
    private fun w(block: W.() -> Unit) = W().apply(block).toByteArray()

    private fun wmi(chat: String, id: String, ts: Long, fromMe: Boolean, participant: String? = null,
                    message: W.() -> Unit, extra: W.() -> Unit = {}): W.() -> Unit = {
        msg(1) { str(1, chat); num(2, if (fromMe) 1 else 0); str(3, id) }
        msg(2, message)
        num(3, ts)
        participant?.let { str(5, it) }
        extra()
    }

    private fun sampleBin(): ByteArray {
        val group = "120363000000000001@g.us"
        val rec = w {
            num(1, 2)
            msg(2) {
                str(1, group)
                msg(2) { msg(1, wmi(group, "AAA1", 1795200000, false, "111@lid", { str(1, "مرحبا") })); num(2, 1000) }
                msg(2) { msg(1, wmi(group, "AAA2", 1795200100, true, null, {
                    msg(3) { str(2, "image/jpeg"); str(3, "صورة"); num(5, 12345)
                        msg(17) { str(1, "AAA1"); msg(3) { str(1, "مرحبا") }; num(22, 1) } }
                }, { str(38, "Media/WhatsApp Images/IMG-1.jpg") })); num(2, 1001) }
                // رسالة نظام: تُتجاهل
                msg(2) { msg(1, wmi(group, "SYS", 1795200200, false, null, { str(1, "x") }, { num(24, 27) })); num(2, 1002) }
                // تفاعل: يُتجاهل
                msg(2) { msg(1, wmi(group, "R1", 1795200300, false, "111@lid", { msg(46) { str(2, "👍") } })); num(2, 1003) }
                // عرض لمرة واحدة
                msg(2) { msg(1, wmi(group, "VO1", 1795200400, false, "111@lid", { msg(55) { msg(1) { msg(9) { str(2, "video/mp4"); num(4, 99) } } } })); num(2, 1004) }
            }
            msg(15) { str(1, "967700000001@s.whatsapp.net"); str(2, "111@lid") }
            msg(18) { str(1, "111@lid"); str(2, "أحمد") }
        }
        return W().apply { varint(rec.size.toLong()); write(rec) }.toByteArray()
    }

    @Test fun parsesHistorySyncMessages() {
        val ov = IncrementOverlay().apply { addMessagesBin(sampleBin()) }
        val msgs = ov.forChat("120363000000000001@g.us").toList()
        assertEquals(listOf("AAA1", "AAA2", "VO1"), msgs.map { it.keyId })
        val a = msgs[0]
        assertEquals(listOf(1000L, 1795200000L, false, "111@lid", "text", "مرحبا"),
            listOf(a.rowId, a.timestamp, a.fromMe, a.senderJid, a.type, a.text))
        val b = msgs[1]
        assertEquals(listOf(true, "image", "صورة", "Media/WhatsApp Images/IMG-1.jpg", "image/jpeg", 12345L, "AAA1", "مرحبا", true),
            listOf(b.fromMe, b.type, b.text, b.mediaPath, b.mime, b.mediaSize, b.quotedId, b.quotedText, b.forwarded))
        assertEquals("view_once_video", msgs[2].type)
        assertEquals("967700000001@s.whatsapp.net", ov.pnOfLid["111@lid"])
        assertEquals("أحمد", ov.lidNames["111@lid"])
    }

    @Test fun laterFileWinsAndAliasLookupWorks() {
        val ov = IncrementOverlay()
        val chat = "967700000002@s.whatsapp.net"
        fun bin(text: String) = w {
            msg(2) { str(1, chat); msg(2) { msg(1, wmi(chat, "K1", 1795200000, false, null, { str(1, text) })); num(2, 5) } }
            msg(15) { str(1, chat); str(2, "222@lid") }
        }.let { W().apply { varint(it.size.toLong()); write(it) }.toByteArray() }
        ov.addMessagesBin(bin("قديم")); ov.addMessagesBin(bin("جديد"))
        assertEquals(listOf("جديد"), ov.forChat(chat).map { it.text })
        assertEquals(listOf("جديد"), ov.forChat("222@lid").map { it.text }) // نفس المحادثة بمعرّف lid
        assertNull(ov.byKey(chat, "nope"))
    }

    @Test fun backupSetPicksCurrentChainOnly() {
        val root = tmp.newFolder("WhatsApp")
        val db = File(root, "Databases").apply { mkdirs() }
        fun put(n: String, s: Long) = File(db, n).apply { writeBytes(ByteArray(10)); setLastModified(s * 1000) }
        put("msgstore.db.crypt15", 1000)
        put("msgstore-increment-1-2026-11-17.1.db.crypt15", 900) // تابع لنسخة قديمة
        put("msgstore-increment-2.db.crypt15", 3000)
        put("msgstore-increment-1.db.crypt15", 2000)
        val set = FileSourceFs(root).backupSet()!!
        assertEquals(listOf("msgstore-increment-1.db.crypt15", "msgstore-increment-2.db.crypt15"), set.increments.map { it.name })
        assertEquals(3000.0, set.mtime, 0.001)
    }

    @Test fun mergesOverlayIntoBaseDb() {
        val out = File(tmp.root, "msgstore.db")
        Crypt15.decrypt(File("src/test/resources/fixtures/wadb/msgstore.db.crypt15"), out, key)
        WaDb(out, JdbcOpener).use { db ->
            val chat = db.listChats().first()
            val before = db.messages(chat, sinceMs = 0)
            val maxRow = before.maxOf { it.rowId }
            val lastTs = before.maxOf { it.timestamp }
            val ov = IncrementOverlay()
            val rec = w {
                msg(2) { str(1, chat.jid)
                    msg(2) { msg(1, wmi(chat.jid, "NEW1", lastTs + 60, false, "333@lid", { str(1, "رسالة جديدة") })); num(2, maxRow + 1) }
                    // نسخة محدّثة من رسالة موجودة (نفس _id): تغلب
                    msg(2) { msg(1, wmi(chat.jid, before.last().keyId, before.last().timestamp, before.last().fromMe, null, { str(1, "نص محدّث") })); num(2, maxRow) }
                }
                msg(15) { str(1, "967700000003@s.whatsapp.net"); str(2, "333@lid") }
            }
            ov.addMessagesBin(W().apply { varint(rec.size.toLong()); write(rec) }.toByteArray())
            db.overlay = ov
            val after = db.messages(chat, sinceMs = 0)
            assertEquals(before.size + 1, after.size)
            assertEquals("رسالة جديدة", after.last().text)
            assertEquals("967700000003@s.whatsapp.net", after.last().senderJid)
            assertEquals(chat.jid, after.last().chatJid)
            assertEquals("نص محدّث", after.first { it.rowId == maxRow }.text)
            // التشغيل التالي: فقط ما بعد آخر _id محفوظ
            val next = db.messages(chat, sinceMs = 0, afterRowId = maxRow)
            assertEquals(listOf("NEW1"), next.map { it.keyId })
            val c2 = db.listChats().first { it.jid == chat.jid }
            assertEquals(chat.messageCount + 1, c2.messageCount)
            assertEquals(lastTs + 60, c2.lastTimestamp)
            assertEquals("NEW1", db.messageByKey(chat, "NEW1")?.keyId)
            assertTrue(db.messageByKey(chat, "NEW1")!!.senderJid.endsWith("@s.whatsapp.net"))
        }
    }
}
