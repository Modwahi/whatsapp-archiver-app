package com.modwahi.waarchiver.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** يقارن StreamingGcm بـ AES/GCM المدمج في Java لأطوال وتقسيمات مختلفة. */
class StreamingGcmTest {
    private fun jceEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(plain) // نص مشفر + 16 بايت وسم
    }

    private fun decryptInPieces(key: ByteArray, iv: ByteArray, ct: ByteArray, rnd: Random): ByteArray {
        val body = ct.size - 16
        val g = StreamingGcm(key, iv)
        val out = ByteArray(body + 32)
        var o = 0
        var p = 0
        while (p < body) {
            val n = minOf(body - p, 1 + rnd.nextInt(5000))
            o += g.update(ct, p, n, out, o)
            p += n
        }
        o += g.finish(ct.copyOfRange(body, ct.size), out, o)
        return out.copyOf(o)
    }

    @Test fun matchesJceForManySizes() {
        val rnd = Random(1)
        for (round in 0 until 60) {
            val key = ByteArray(32).also(rnd::nextBytes)
            val iv = ByteArray(if (round % 3 == 0) 12 else 16).also(rnd::nextBytes)
            val plain = ByteArray(rnd.nextInt(70_000)).also(rnd::nextBytes)
            assertArrayEquals(plain, decryptInPieces(key, iv, jceEncrypt(key, iv, plain), rnd))
        }
    }

    /** العدّاد يزيد آخر 32 بت فقط (inc32): نبحث عن IV يجعل العدّاد يلتف أثناء الرسالة. */
    @Test fun counterWrapAround() {
        val rnd = Random(2)
        val key = ByteArray(32).also(rnd::nextBytes)
        val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        val blocks = 8192
        var found: ByteArray? = null
        for (t in 0 until 5_000_000) {
            val iv = ByteArray(16).also(rnd::nextBytes)
            val j0 = j0Of(key, iv)
            val low = ((j0[12].toLong() and 0xff) shl 24) or ((j0[13].toLong() and 0xff) shl 16) or
                ((j0[14].toLong() and 0xff) shl 8) or (j0[15].toLong() and 0xff)
            if (low > 0xffffffffL - blocks) { found = iv; break }
        }
        val iv = found ?: return fail("لم أجد IV مناسباً")
        val plain = ByteArray(blocks * 16 + 7).also(rnd::nextBytes)
        assertArrayEquals(plain, decryptInPieces(key, iv, jceEncrypt(key, iv, plain), rnd))
        assertTrue(ecb.blockSize == 16)
    }

    private fun j0Of(key: ByteArray, iv: ByteArray): ByteArray {
        val f = StreamingGcm::class.java.getDeclaredField("j0").apply { isAccessible = true }
        return f.get(StreamingGcm(key, iv)) as ByteArray
    }

    @Test fun wrongTagRejected() {
        val rnd = Random(3)
        val key = ByteArray(32).also(rnd::nextBytes)
        val iv = ByteArray(16).also(rnd::nextBytes)
        val ct = jceEncrypt(key, iv, ByteArray(1000).also(rnd::nextBytes))
        ct[10] = (ct[10].toInt() xor 1).toByte()
        try { decryptInPieces(key, iv, ct, rnd); fail() } catch (e: DecryptError) { }
    }
}
