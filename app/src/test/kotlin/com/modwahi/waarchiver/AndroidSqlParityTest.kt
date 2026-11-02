package com.modwahi.waarchiver

import com.modwahi.waarchiver.core.ArchLog
import com.modwahi.waarchiver.core.ArchiveConfig
import com.modwahi.waarchiver.core.ChatSpec
import com.modwahi.waarchiver.core.Crypt15
import com.modwahi.waarchiver.core.FileSourceFs
import com.modwahi.waarchiver.core.JObj
import com.modwahi.waarchiver.core.Json
import com.modwahi.waarchiver.core.LocalUploader
import com.modwahi.waarchiver.core.Paths
import com.modwahi.waarchiver.core.WaDb
import com.modwahi.waarchiver.core.runArchive
import com.modwahi.waarchiver.io.AndroidSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

/**
 * نفس مقارنة Python لكن عبر محوّل SQLite الخاص بأندرويد (android.database.sqlite) بدل sqlite-jdbc:
 * يتحقق أن ربط المعاملات بأنواعها وقراءة الأعمدة يعطيان نفس الناتج بايتاً ببايت.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidSqlParityTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fixtures = File("../core/src/test/resources/fixtures")
    private val key = File(fixtures, "key.txt").readText().trim()

    private object NoLog : ArchLog {
        override fun info(msg: String) {}
        override fun warning(msg: String) {}
        override fun error(msg: String) {}
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun readerMatchesPython() {
        val db = File(tmp.root, "m.db")
        Crypt15.decrypt(File(fixtures, "wadb/msgstore.db.crypt15"), db, key)
        val exp = Json.parse(File(fixtures, "wadb/expected.json").readText()) as JObj
        WaDb(db, AndroidSql).use { w ->
            val chats = w.listChats()
            assertEquals((exp["chats"] as List<JObj>).map { it["jid"] }, chats.map { it.jid })
            for (c in chats) {
                val em = (exp["messages"] as JObj)[c.jid] as List<JObj>
                val msgs = w.messages(c, sinceMs = 0)
                assertEquals(em.map { listOf(it["key_id"], it["sender_jid"], it["text"], it["quoted_text"], it["push_name"],
                    it["media_path"], it["forwarded"]) },
                    msgs.map { listOf(it.keyId, it.senderJid, it.text, it.quotedText, it.pushName, it.mediaPath, it.forwarded) })
            }
        }
    }

    private fun tree(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }

    @Suppress("UNCHECKED_CAST")
    @Test fun fullSequenceMatchesPython() {
        val dir = File(fixtures, "full_sequence")
        val wa = File(tmp.root, "WhatsApp")
        val remote = File(tmp.root, "drive")
        val paths = Paths(File(tmp.root, "home"))
        for (n in 1..3) {
            val sd = File(dir, "step$n")
            val step = Json.parse(File(sd, "step.json").readText()) as JObj
            val c = step["config"] as JObj
            wa.deleteRecursively()
            File(sd, "input/wa").copyRecursively(wa)
            val target = File(wa, "Databases/msgstore.db.crypt15").apply { parentFile!!.mkdirs() }
            File(sd, "input/msgstore.db.crypt15").copyTo(target, overwrite = true)
            target.setLastModified(Math.round((step["backup_mtime"] as Double) * 1000))
            val cfg = ArchiveConfig(
                chats = (c["chats"] as List<JObj>).map { ChatSpec(it["jid"] as String, it["name"] as String) },
                firstRunDays = 7, maxMediaMb = 1, zone = ZoneId.of("Asia/Aden"))
            val s = runArchive(cfg, paths, key, FileSourceFs(wa), LocalUploader(remote), NoLog, AndroidSql,
                contacts = (c["contacts"] as JObj).mapValues { it.value as String }, now = step["now"] as Double)!!
            assertEquals(File(sd, "expected/summary.txt").readText(), s.render())
            assertEquals("step$n", tree(File(sd, "expected/remote")), tree(remote))
        }
        assertTrue(File(remote, "_سجل التشغيل").exists())
    }
}
