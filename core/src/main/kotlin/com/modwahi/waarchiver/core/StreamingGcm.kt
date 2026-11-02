package com.modwahi.waarchiver.core

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * فك AES-GCM على دفعات (streaming) باستخدام AES/ECB المدمج في النظام فقط.
 *
 * GCM في Java/Android لا يُخرج أي نص قبل التحقق من الوسم في النهاية (يحتفظ بالملف كله في الذاكرة)،
 * فنطبّق GCM هنا بأنفسنا: عدّاد CTR (inc32) + GHASH، ونتحقق من الوسم في finish().
 * التطبيق منقول من crypto/cipher/gcm.go (الإصدار العام) في مكتبة Go القياسية.
 */
class StreamingGcm(key: ByteArray, iv: ByteArray) {
    private val ecb: Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }

    // عنصر الحقل: low = أول 8 بايت، high = آخر 8 بايت (كما في Go)
    private val tableLow = LongArray(16)
    private val tableHigh = LongArray(16)
    private var yLow = 0L
    private var yHigh = 0L
    private val j0: ByteArray
    private val counter: ByteArray
    private var cipherLen = 0L
    private val pending = ByteArray(16)
    private var pendingLen = 0

    init {
        val h = ecb.doFinal(ByteArray(16))
        val xLow = be64(h, 0)
        val xHigh = be64(h, 8)
        tableLow[reverseBits(1)] = xLow
        tableHigh[reverseBits(1)] = xHigh
        var i = 2
        while (i < 16) {
            val src = reverseBits(i / 2)
            val msbSet = tableHigh[src] and 1L == 1L
            var dh = (tableHigh[src] ushr 1) or (tableLow[src] shl 63)
            var dl = tableLow[src] ushr 1
            if (msbSet) dl = dl xor -0x1f00000000000000L // 0xe100000000000000
            tableLow[reverseBits(i)] = dl
            tableHigh[reverseBits(i)] = dh
            tableLow[reverseBits(i + 1)] = dl xor xLow
            tableHigh[reverseBits(i + 1)] = dh xor xHigh
            i += 2
        }
        // J0
        j0 = ByteArray(16)
        if (iv.size == 12) {
            System.arraycopy(iv, 0, j0, 0, 12)
            j0[15] = 1
        } else {
            ghashBlocks(iv, 0, iv.size)
            yHigh = yHigh xor (iv.size.toLong() * 8)
            mul()
            put64(j0, 0, yLow)
            put64(j0, 8, yHigh)
            yLow = 0; yHigh = 0
        }
        counter = j0.copyOf()
        inc32(counter)
    }

    companion object {
        private val REDUCTION = longArrayOf(
            0x0000, 0x1c20, 0x3840, 0x2460, 0x7080, 0x6ca0, 0x48c0, 0x54e0,
            0xe100, 0xfd20, 0xd940, 0xc560, 0x9180, 0x8da0, 0xa9c0, 0xb5e0,
        )

        private fun reverseBits(x: Int): Int {
            var i = ((x shl 2) and 0xc) or ((x shr 2) and 0x3)
            i = ((i shl 1) and 0xa) or ((i shr 1) and 0x5)
            return i
        }

        private fun be64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (b[o + k].toLong() and 0xff)
            return v
        }

        private fun put64(b: ByteArray, o: Int, v: Long) {
            for (k in 0 until 8) b[o + k] = (v ushr (56 - 8 * k)).toByte()
        }

        internal fun inc32(c: ByteArray) {
            var i = 15
            while (i >= 12) {
                c[i] = (c[i] + 1).toByte()
                if (c[i].toInt() != 0) return
                i--
            }
        }
    }

    private fun mul() {
        var zLow = 0L
        var zHigh = 0L
        for (w in 0 until 2) {
            var word = if (w == 0) yHigh else yLow
            for (j in 0 until 16) {
                val msw = (zHigh and 0xf).toInt()
                zHigh = (zHigh ushr 4) or (zLow shl 60)
                zLow = (zLow ushr 4) xor (REDUCTION[msw] shl 48)
                val t = (word and 0xf).toInt()
                zLow = zLow xor tableLow[t]
                zHigh = zHigh xor tableHigh[t]
                word = word ushr 4
            }
        }
        yLow = zLow
        yHigh = zHigh
    }

    /** GHASH لكتل كاملة، وآخر كتلة ناقصة تُكمَّل بأصفار. */
    private fun ghashBlocks(b: ByteArray, off: Int, len: Int) {
        var o = off
        val end = off + len
        while (end - o >= 16) {
            yLow = yLow xor be64(b, o)
            yHigh = yHigh xor be64(b, o + 8)
            mul()
            o += 16
        }
        if (o < end) {
            val tmp = ByteArray(16)
            System.arraycopy(b, o, tmp, 0, end - o)
            yLow = yLow xor be64(tmp, 0)
            yHigh = yHigh xor be64(tmp, 8)
            mul()
        }
    }

    private var ksBuf = ByteArray(0)

    /** يفك n كتلة كاملة من src إلى dst (CTR مع inc32) ويضيفها لـ GHASH. */
    private fun cryptBlocks(src: ByteArray, srcOff: Int, n: Int, dst: ByteArray, dstOff: Int) {
        if (n == 0) return
        val len = n * 16
        if (ksBuf.size < len) ksBuf = ByteArray(len)
        for (k in 0 until n) {
            System.arraycopy(counter, 0, ksBuf, k * 16, 16)
            inc32(counter)
        }
        ecb.doFinal(ksBuf, 0, len, ksBuf, 0)
        ghashBlocks(src, srcOff, len)
        for (k in 0 until len) dst[dstOff + k] = (src[srcOff + k].toInt() xor ksBuf[k].toInt()).toByte()
    }

    /** أقصى حجم ناتج لاستدعاء update بطول len. */
    fun outputSize(len: Int): Int = ((pendingLen + len) / 16) * 16

    /** يفك ما أمكن من الكتل الكاملة. يرجع عدد البايتات المكتوبة في out (يحتفظ بأقل من 16 بايت للنهاية). */
    fun update(input: ByteArray, off: Int, len: Int, out: ByteArray, outOff: Int): Int {
        cipherLen += len
        var o = off
        var remaining = len
        var written = 0
        if (pendingLen > 0) {
            val take = minOf(16 - pendingLen, remaining)
            System.arraycopy(input, o, pending, pendingLen, take)
            pendingLen += take
            o += take
            remaining -= take
            if (pendingLen < 16) return 0
            cryptBlocks(pending, 0, 1, out, outOff)
            pendingLen = 0
            written = 16
        }
        val blocks = remaining / 16
        cryptBlocks(input, o, blocks, out, outOff + written)
        written += blocks * 16
        o += blocks * 16
        remaining -= blocks * 16
        if (remaining > 0) {
            System.arraycopy(input, o, pending, 0, remaining)
            pendingLen = remaining
        }
        return written
    }

    /** يكتب آخر جزء (أقل من 16 بايت) في out ويرجع طوله، ويتحقق من وسم المصادقة. */
    fun finish(tag: ByteArray, out: ByteArray, outOff: Int): Int {
        var n = 0
        if (pendingLen > 0) {
            val ks = ecb.doFinal(counter)
            ghashBlocks(pending, 0, pendingLen)
            for (k in 0 until pendingLen) out[outOff + k] = (pending[k].toInt() xor ks[k].toInt()).toByte()
            n = pendingLen
            pendingLen = 0
        }
        // كتلة الأطوال: طول البيانات الإضافية (صفر) وطول النص المشفر بالبت
        yHigh = yHigh xor (cipherLen * 8)
        mul()
        val s = ByteArray(16)
        put64(s, 0, yLow)
        put64(s, 8, yHigh)
        val ekj0 = ecb.doFinal(j0)
        val expected = ByteArray(16) { (s[it].toInt() xor ekj0[it].toInt()).toByte() }
        if (!MessageDigest.isEqual(expected, tag)) throw DecryptError("فشل التحقق من سلامة النسخة (الملف تالف أو المفتاح خاطئ)")
        return n
    }
}
