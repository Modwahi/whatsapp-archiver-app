package com.modwahi.waarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager

class WaDbTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = File("src/test/resources/fixtures/key.txt").readText().trim()

    private fun decrypted(): File {
        val out = File(tmp.root, "msgstore.db")
        Crypt15.decrypt(File("src/test/resources/fixtures/wadb/msgstore.db.crypt15"), out, key)
        return out
    }

    /** نفس ما قرأته wadb.py من نفس القاعدة: المحادثات (بالترتيب) وكل حقول كل رسالة. */
    @Suppress("UNCHECKED_CAST")
    @Test fun matchesPythonReader() {
        val exp = Json.parse(File("src/test/resources/fixtures/wadb/expected.json").readText()) as JObj
        WaDb(decrypted(), JdbcOpener).use { db ->
            val chats = db.listChats()
            val expChats = exp["chats"] as List<JObj>
            assertEquals(expChats.map { it["jid"] }, chats.map { it.jid })
            for ((e, c) in expChats.zip(chats)) {
                assertEquals(listOf(e["row_id"], e["subject"], e["is_group"], e["message_count"], e["last_timestamp"]),
                    listOf(c.rowId, c.subject, c.isGroup, c.messageCount, c.lastTimestamp))
            }
            val expMsgs = exp["messages"] as JObj
            for (c in chats) {
                val msgs = db.messages(c, sinceMs = 0)
                val em = expMsgs[c.jid] as List<JObj>
                assertEquals(c.jid, em.size, msgs.size)
                for ((e, m) in em.zip(msgs)) {
                    val actual = listOf(m.rowId, m.keyId, m.chatJid, m.timestamp, m.fromMe, m.senderJid, m.type, m.text,
                        m.mediaPath, m.mime, m.mediaName, m.mediaSize, m.quotedText, m.quotedId, m.forwarded, m.pushName,
                        m.hasMedia)
                    val expected = listOf(e["row_id"], e["key_id"], e["chat_jid"], e["timestamp"], e["from_me"],
                        e["sender_jid"], e["type"], e["text"], e["media_path"], e["mime"], e["media_name"], e["media_size"],
                        e["quoted_text"], e["quoted_id"], e["forwarded"], e["push_name"], (e["extra"] as JObj)["has_media"])
                    assertEquals(m.keyId, expected, actual)
                }
            }
        }
    }

    @Test fun optionalTablesMissing() {
        val f = decrypted()
        DriverManager.getConnection("jdbc:sqlite:" + f.path).use { c ->
            for (t in listOf("message_quoted", "message_forwarded", "jid_map", "lid_display_name", "message_media")) {
                c.createStatement().execute("DROP TABLE $t")
            }
        }
        WaDb(f, JdbcOpener).use { db ->
            val msgs = db.messages(db.findChat("120363111111111111@g.us")!!, sinceMs = 0)
            assertEquals(8, msgs.size)
            assertTrue(msgs.all { it.quotedText == null && !it.forwarded })
        }
    }

    @Test fun missingRequiredColumnGivesClearError() {
        val p = File(tmp.root, "bad.db")
        DriverManager.getConnection("jdbc:sqlite:" + p.path).use { c ->
            c.createStatement().execute("CREATE TABLE message (_id INTEGER, chat_row_id INTEGER, from_me INTEGER)")
            c.createStatement().execute("CREATE TABLE jid (_id INTEGER, raw_string TEXT)")
        }
        try {
            WaDb(p, JdbcOpener); fail()
        } catch (e: SchemaError) {
            val msg = e.message!!
            assertTrue(msg, "الجدول chat غير موجود" in msg && "key_id" in msg && "timestamp" in msg)
        }
    }

    @Test fun legacySchemaHint() {
        val p = File(tmp.root, "old.db")
        DriverManager.getConnection("jdbc:sqlite:" + p.path).use { c ->
            c.createStatement().execute("CREATE TABLE messages (_id INTEGER, key_remote_jid TEXT)")
        }
        try { WaDb(p, JdbcOpener); fail() } catch (e: SchemaError) { assertTrue("قديم" in e.message!!) }
    }

    @Test fun notSqlite() {
        val p = File(tmp.root, "x.db").apply { writeBytes("garbage".repeat(100).toByteArray()) }
        try { WaDb(p, JdbcOpener); fail() } catch (e: SchemaError) { }
    }
}
