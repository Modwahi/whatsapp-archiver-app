package com.modwahi.waarchiver.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ProtoSchemaTest {
    private fun ByteArrayOutputStream.varint(v: Long) { var x = v; while (true) { if (x < 0x80) { write(x.toInt()); return }; write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 } }
    private fun ByteArrayOutputStream.tag(f: Int, w: Int) = varint(((f shl 3) or w).toLong())
    private fun ByteArrayOutputStream.bytes(f: Int, b: ByteArray) { tag(f, 2); varint(b.size.toLong()); write(b) }
    private fun msg(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()

    @Test fun infersStructureWithoutLeakingText() {
        val inner = msg {
            bytes(1, "967700000000@s.whatsapp.net".toByteArray())
            tag(2, 0); varint(1763700000000)
            bytes(3, "مرحبا يا صديقي العزيز".toByteArray())
            bytes(4, "3EB0A1B2C3D4E5F6A7B8".toByteArray())
            tag(5, 0); varint(1)
        }
        val record = msg { tag(1, 0); varint(2); bytes(2, msg { bytes(1, inner); bytes(1, inner) }) }
        val file = msg { varint(record.size.toLong()); write(record); varint(record.size.toLong()); write(record) }
        val ps = ProtoSchema(); ps.addDelimited(file)
        val r = ps.report("")
        println(r)
        assertTrue(r, r.contains("سجلات: 2"))
        assertTrue(r, r.contains("2.1.1:") && r.contains("@s.whatsapp.net"))
        assertTrue(r, r.contains("وقت ms"))
        assertTrue(r, r.contains("2.1.3:") && r.contains("نص"))
        assertTrue(r, r.contains("hex"))
        assertFalse(r, r.contains("مرحبا") || r.contains("967700000000") || r.contains("3EB0"))
    }
}
