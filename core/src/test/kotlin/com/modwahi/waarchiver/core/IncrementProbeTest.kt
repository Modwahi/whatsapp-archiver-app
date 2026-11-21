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
