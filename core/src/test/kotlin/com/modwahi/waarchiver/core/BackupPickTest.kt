package com.modwahi.waarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupPickTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun put(dir: File, name: String, mtimeS: Long) =
        File(dir, name).apply { writeBytes(ByteArray(10)); setLastModified(mtimeS * 1000) }

    @Test fun picksNewestIncludingDatedDailyBackup() {
        val root = tmp.newFolder("WhatsApp")
        val db = File(root, "Databases").apply { mkdirs() }
        put(db, "msgstore.db.crypt15", 1_000)          // نسخة يدوية قديمة
        put(db, "msgstore-2026-11-20.1.db.crypt15", 2_000)
        put(db, "msgstore-2026-11-21.1.db.crypt15", 3_000) // النسخة الليلية الأحدث
        put(db, "wa.db.crypt15", 9_000)                 // ليست نسخة رسائل
        assertEquals("msgstore-2026-11-21.1.db.crypt15", FileSourceFs(root).backup()!!.name)
    }

    @Test fun prefersFixedNameOnTie() {
        val root = tmp.newFolder("WhatsApp")
        val db = File(root, "Databases").apply { mkdirs() }
        put(db, "msgstore-2026-11-21.1.db.crypt15", 3_000)
        put(db, "msgstore.db.crypt15", 3_000)
        assertEquals("msgstore.db.crypt15", FileSourceFs(root).backup()!!.name)
    }

    @Test fun noneWhenMissing() {
        assertNull(FileSourceFs(tmp.newFolder("WhatsApp")).backup())
    }
}
