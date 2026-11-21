package com.modwahi.waarchiver.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IncrementProbeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = File("src/test/resources/fixtures/key.txt").readText().trim()

    @Test fun describesEncryptedSqliteWithoutLeavingFiles() {
        val root = tmp.newFolder("WhatsApp")
        val db = File(root, "Databases").apply { mkdirs() }
        File("src/test/resources/fixtures/crypto/big.crypt15").copyTo(File(db, "msgstore-increment-2.db.crypt15"))
        File(db, "msgstore-increment-1.db.crypt15").writeBytes(ByteArray(100))
        val src = FileSourceFs(root)
        assertTrue(src.increments().size == 2)
        val work = tmp.newFolder("work")
        val r = IncrementProbe.report(src.backup(), src.increments(), key, work, JdbcOpener)
        println(r)
        assertTrue(r, r.contains("msgstore-increment-2.db.crypt15"))
        assertTrue(r, r.contains("جداول:"))
        assertTrue(r, r.contains("فشل الفك") || r.contains("خطأ"))
        assertFalse(work.listFiles()!!.any { it.name.startsWith("probe") })
    }
}

class IncrementProbeZipTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun listsZipEntriesAndDescribesSqliteAndJsonKeys() {
        val dbFile = File(tmp.root, "inner.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${dbFile.path}").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE message(_id INTEGER PRIMARY KEY, chat_row_id INT, text_data TEXT)") }
            c.createStatement().use { it.execute("INSERT INTO message VALUES (5, 2, 'سري')") }
        }
        val zipFile = File(tmp.root, "x.zip")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("db/msgstore.db")); z.write(dbFile.readBytes()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("meta.json")); z.write("""{"version": 3, "rows": [{"key": "سري"}]}""".toByteArray()); z.closeEntry()
        }
        val out = StringBuilder()
        IncrementProbe.describeZip(zipFile, tmp.newFolder("w"), JdbcOpener, out)
        val r = out.toString()
        println(r)
        assertTrue(r, r.contains("db/msgstore.db") && r.contains("SQLite") && r.contains("- message: 1 صف"))
        assertFalse(r, r.contains("مفاتيح"))
        assertFalse("لا محتوى رسائل", r.contains("سري"))
    }
}
