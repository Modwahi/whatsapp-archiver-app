package com.modwahi.waarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** ملفات crypt15 صنعتها wa-crypt-tools (عبر أداة Python) بمفتاح معروف. */
class CryptoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dir = File("src/test/resources/fixtures/crypto")
    private val key = File("src/test/resources/fixtures/key.txt").readText().trim()
    private val expected = File(dir, "plain.sha256").readText().trim().split(" ")

    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun assertDecryptFails(enc: File, k: String, contains: String? = null) {
        val out = File(tmp.root, "o.db")
        try {
            Crypt15.decrypt(enc, out, k)
            fail("كان يجب أن يفشل")
        } catch (e: DecryptError) {
            if (contains != null) assertTrue(e.message, e.message!!.contains(contains))
        }
        assertFalse("لا يبقى ملف مفكوك جزئياً", out.exists())
    }

    @Test fun roundtripBiggerThanOneChunk() {
        for (name in listOf("big.crypt15", "multifile.crypt15")) {
            val enc = File(dir, name)
            Crypt15.verifyKey(enc.inputStream(), key)
            val out = File(tmp.root, "out/$name.db")
            val n = Crypt15.decrypt(enc, out, key)
            assertTrue("أكبر من دفعة واحدة", n > 2 * Crypt15.CHUNK)
            assertEquals(expected[1].toLong(), n)
            assertEquals(name, expected[0], sha256(out))
        }
    }

    @Test fun keyIsCleaned() {
        val spaced = key.chunked(4).joinToString(" ").uppercase()
        assertTrue(Crypt15.isValidKey(spaced))
        Crypt15.verifyKey(File(dir, "big.crypt15").inputStream(), spaced)
        for (bad in listOf("123", "z".repeat(64), "")) assertFalse(Crypt15.isValidKey(bad))
    }

    @Test fun wrongKey() {
        val enc = File(dir, "big.crypt15")
        val other = "b".repeat(64)
        try {
            Crypt15.verifyKey(enc.inputStream(), other)
            fail()
        } catch (e: DecryptError) {
            assertTrue(e.message!!.contains("لا يطابق"))
        }
        assertDecryptFails(enc, other, "لا يطابق")
    }

    @Test fun truncated() {
        val data = File(dir, "big.crypt15").readBytes()
        val enc = File(tmp.root, "t.crypt15").apply { writeBytes(data.copyOf(data.size / 2)) }
        assertDecryptFails(enc, key)
    }

    @Test fun tampered() {
        val data = File(dir, "big.crypt15").readBytes()
        data[data.size / 2] = (data[data.size / 2].toInt() xor 0xff).toByte()
        val enc = File(tmp.root, "x.crypt15").apply { writeBytes(data) }
        assertDecryptFails(enc, key)
    }

    @Test fun sourceNotModified() {
        val src = File(tmp.root, "s.crypt15")
        File(dir, "big.crypt15").copyTo(src)
        src.setLastModified(1_700_000_000_000)
        val before = src.readBytes().toList() to src.lastModified()
        Crypt15.decrypt(src, File(tmp.root, "o.db"), key)
        assertEquals(before, src.readBytes().toList() to src.lastModified())
    }

    @Test fun notABackup() {
        val enc = File(tmp.root, "z.crypt15").apply { writeBytes(ByteArray(100)) }
        assertDecryptFails(enc, key)
        val enc2 = File(tmp.root, "e.crypt15").apply { writeBytes(ByteArray(0)) }
        assertDecryptFails(enc2, key)
    }
}
