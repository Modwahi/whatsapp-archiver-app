package com.modwahi.waarchiver.core

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DecryptError(message: String) : Exception(message)

/**
 * فك تشفير msgstore.db.crypt15 بمفتاح 64 hex — منقول من wa_archiver/crypto.py.
 *
 * الصيغة (كما في wa-crypt-tools):
 *   [1 بايت: طول protobuf] [0x01 اختياري: جدول الميزات] [protobuf BackupPrefix]
 *   [بيانات AES-GCM لنص مضغوط zlib] [16 بايت وسم المصادقة] [16 بايت md5 لكل ما سبق]
 * النسخ متعددة الأجزاء ليس فيها md5 في النهاية.
 *
 * نفك AES-GCM بـ StreamingGcm لأنه يُخرج النص على دفعات، بينما GCM في Java/Android
 * يحتفظ بالملف كله في الذاكرة حتى النهاية.
 */
object Crypt15 {
    const val CHUNK = 1024 * 1024
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** يزيل المسافات والشرطات والنقطتين من المفتاح ويحوّله لحروف صغيرة. */
    fun cleanKey(text: String): String = text.replace(Regex("[\\s\\-:]"), "").lowercase()

    fun isValidKey(key: String): Boolean = Regex("[0-9a-f]{64}").matches(cleanKey(key))

    /** المفتاح المستخدم للتشفير (ليس المفتاح الجذري): encryptionloop(root, "backup encryption") */
    internal fun derive(rootHex: String): ByteArray {
        val key = cleanKey(rootHex)
        if (!Regex("[0-9a-f]{64}").matches(key)) throw DecryptError("المفتاح غير صالح (يجب أن يكون 64 خانة hex)")
        val root = ByteArray(32) { key.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val privateKey = mac.doFinal(root)
        mac.init(SecretKeySpec(privateKey, "HmacSHA256"))
        mac.update("backup encryption".toByteArray(Charsets.US_ASCII))
        mac.update(1)
        return mac.doFinal().copyOf(32)
    }

    private class Header(val cipher: StreamingGcm, val md5: MessageDigest, val length: Int)

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    /** يقرأ رأس الملف ويجهّز cipher. المؤشر يقف عند بداية البيانات المشفرة. */
    private fun openHeader(input: BufferedInputStream, keyHex: String): Header {
        val key = derive(keyHex)
        val md5 = MessageDigest.getInstance("MD5")
        var length = 0
        val iv: ByteArray
        try {
            val sizeByte = input.read()
            if (sizeByte < 0) throw IOException("empty")
            md5.update(sizeByte.toByte())
            length++
            input.mark(1)
            val flag = input.read()
            if (flag == 1) {
                md5.update(1.toByte())
                length++
            } else {
                input.reset()
            }
            val proto = readFully(input, sizeByte)
            if (proto.size != sizeByte) throw IOException("short header")
            md5.update(proto)
            length += proto.size
            val prefix = parseBackupPrefix(proto)
            if (prefix.c15Iv == null) {
                val type = if (prefix.hasC14) "Database14" else "Database12"
                throw DecryptError("الملف ليس بصيغة crypt15 (النوع: $type)")
            }
            iv = prefix.c15Iv
        } catch (e: DecryptError) {
            throw e
        } catch (e: Exception) {
            throw DecryptError("تعذّر قراءة رأس ملف النسخة الاحتياطية: ${e.javaClass.simpleName}")
        }
        return Header(StreamingGcm(key, iv), md5, length)
    }

    private class Prefix(val c15Iv: ByteArray?, val hasC14: Boolean)

    /** قارئ protobuf صغير لـ BackupPrefix: 1 key_type, 2 c14_cipher, 3 c15_iv{1: IV}, 4 info */
    private fun parseBackupPrefix(b: ByteArray): Prefix {
        var c15: ByteArray? = null
        var c14 = false
        val r = ProtoReader(b, 0, b.size)
        while (r.hasMore()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            when (val wire = (tag and 7).toInt()) {
                2 -> {
                    val (start, end) = r.lengthDelimited()
                    if (field == 3) {
                        val inner = ProtoReader(b, start, end)
                        while (inner.hasMore()) {
                            val t = inner.varint()
                            if ((t ushr 3).toInt() == 1 && (t and 7).toInt() == 2) {
                                val (s, e) = inner.lengthDelimited()
                                c15 = b.copyOfRange(s, e)
                            } else inner.skip((t and 7).toInt())
                        }
                    } else if (field == 2 && end > start) {
                        c14 = true
                    }
                }
                else -> r.skip(wire)
            }
        }
        return Prefix(c15?.takeIf { it.isNotEmpty() }, c14)
    }

    private class ProtoReader(val b: ByteArray, var pos: Int, val end: Int) {
        fun hasMore() = pos < end
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (pos >= end) throw IOException("truncated varint")
                val x = b[pos++].toInt() and 0xff
                result = result or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("bad varint")
            }
        }
        fun lengthDelimited(): Pair<Int, Int> {
            val len = varint().toInt()
            if (len < 0 || pos + len > end) throw IOException("bad length")
            val s = pos
            pos += len
            return s to pos
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> lengthDelimited()
                5 -> pos += 4
                else -> throw IOException("bad wire type $wire")
            }
            if (pos > end) throw IOException("truncated")
        }
    }

    /** فك تجريبي سريع لأول جزء من الملف: يتأكد أن المفتاح يطابق النسخة. يرمي DecryptError إن لم يطابق. */
    fun verifyKey(input: InputStream, keyHex: String, probeBytes: Int = 256 * 1024) {
        val inp = BufferedInputStream(input, 64 * 1024)
        val h = openHeader(inp, keyHex)
        val chunk = readFully(inp, probeBytes)
        val plain = ByteArray(h.cipher.outputSize(chunk.size))
        val n = h.cipher.update(chunk, 0, chunk.size, plain, 0)
        val inf = Inflater()
        val out = ByteArray(SQLITE_MAGIC.size)
        var got = 0
        try {
            inf.setInput(plain, 0, n)
            while (got < out.size && !inf.finished() && !inf.needsInput()) {
                val r = inf.inflate(out, got, out.size - got)
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                got += r
            }
        } catch (e: DataFormatException) {
            got = 0
        } finally {
            inf.end()
        }
        if (got < out.size || !out.contentEquals(SQLITE_MAGIC)) {
            throw DecryptError("المفتاح لا يطابق ملف النسخة الاحتياطية (فشل الفك التجريبي)")
        }
    }

    /**
     * يفك ملف crypt15 إلى output (قاعدة SQLite). يرجع حجم الناتج بالبايت.
     * size هو حجم الملف المشفر كاملاً. عند أي فشل يُحذف الناتج الجزئي.
     */
    fun decrypt(input: InputStream, size: Long, output: File, keyHex: String,
                onProgress: ((Double) -> Unit)? = null): Long {
        output.parentFile?.mkdirs()
        var ok = false
        var written = 0L
        val inf = Inflater()
        try {
            BufferedInputStream(input, CHUNK).use { inp ->
                FileOutputStream(output).use { out ->
                    restrictPermissions(output)
                    val h = openHeader(inp, keyHex)
                    val cipher = h.cipher
                    var remaining = size - h.length - 32
                    if (remaining <= 0) throw DecryptError("ملف النسخة الاحتياطية فارغ أو مقطوع")
                    val total = remaining
                    val buf = ByteArray(CHUNK)
                    val plain = ByteArray(CHUNK + 64)
                    val outBuf = ByteArray(4 * CHUNK)
                    var first = true

                    fun inflateAll(data: ByteArray, len: Int, isFirst: Boolean) {
                        if (len == 0 || inf.finished()) return
                        inf.setInput(data, 0, len)
                        var firstOut = isFirst
                        var produced = 0L
                        val head = ByteArray(6)
                        var headLen = 0
                        try {
                            while (!inf.finished()) {
                                val r = inf.inflate(outBuf)
                                if (r == 0) {
                                    if (inf.needsInput() || inf.needsDictionary()) break
                                    continue
                                }
                                if (firstOut && headLen < 6) {
                                    val take = minOf(6 - headLen, r)
                                    System.arraycopy(outBuf, 0, head, headLen, take)
                                    headLen += take
                                }
                                out.write(outBuf, 0, r)
                                produced += r
                            }
                        } catch (e: DataFormatException) {
                            throw DecryptError(
                                if (isFirst) "المفتاح لا يطابق ملف النسخة الاحتياطية" else "بيانات النسخة تالفة (zlib)"
                            )
                        }
                        if (firstOut && headLen >= 6) {
                            for (k in 0 until 6) if (head[k] != SQLITE_MAGIC[k]) {
                                throw DecryptError("المفتاح لا يطابق ملف النسخة الاحتياطية")
                            }
                            firstOut = false
                        }
                        written += produced
                    }

                    while (remaining > 0) {
                        val want = minOf(CHUNK.toLong(), remaining).toInt()
                        val n = inp.read(buf, 0, want)
                        if (n <= 0) throw DecryptError("ملف النسخة الاحتياطية مقطوع (انتهى قبل المتوقع)")
                        remaining -= n
                        h.md5.update(buf, 0, n)
                        val p = cipher.update(buf, 0, n, plain, 0)
                        inflateAll(plain, p, first)
                        first = false
                        onProgress?.invoke(1.0 - remaining.toDouble() / total)
                    }
                    val trailer = readFully(inp, 32)
                    if (trailer.size != 32) throw DecryptError("ملف النسخة الاحتياطية مقطوع")
                    h.md5.update(trailer, 0, 16)
                    val checksum = trailer.copyOfRange(16, 32)
                    // إن طابق md5: أول 16 هي وسم المصادقة. وإلا (multi-file): آخر 16 هي الوسم.
                    val feed = if (h.md5.digest().contentEquals(checksum)) 16 else 32
                    var p = if (feed == 32) cipher.update(trailer, 0, 16, plain, 0) else 0
                    p += cipher.finish(trailer.copyOfRange(feed - 16, feed), plain, p)
                    inflateAll(plain, p, first)
                    if (!inf.finished()) throw DecryptError("النسخة الاحتياطية مقطوعة (ضغط zlib غير مكتمل)")
                }
            }
            output.inputStream().use { check ->
                if (!readFully(check, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)) {
                    throw DecryptError("الناتج ليس قاعدة SQLite")
                }
            }
            ok = true
            return written
        } finally {
            inf.end()
            if (!ok) output.delete()
        }
    }

    fun decrypt(encrypted: File, output: File, keyHex: String): Long =
        decrypt(encrypted.inputStream(), encrypted.length(), output, keyHex)

    private fun restrictPermissions(f: File) {
        f.setReadable(false, false); f.setReadable(true, true)
        f.setWritable(false, false); f.setWritable(true, true)
    }
}
