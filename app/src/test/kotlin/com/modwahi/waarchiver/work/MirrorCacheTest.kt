package com.modwahi.waarchiver.work

import com.modwahi.waarchiver.core.JSON_NAME
import com.modwahi.waarchiver.core.Uploader
import com.modwahi.waarchiver.io.RemoteSizes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** قراءة ملف اليوم من نسخة القراءة على التلفون بدل تنزيله، فقط إن طابق حجمه ما في الوجهة. */
class MirrorCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    class Fake(val files: Map<String, ByteArray>) : Uploader, RemoteSizes {
        var downloads = 0
        override fun remoteSize(relDir: String, name: String) = files["$relDir/$name"]?.size?.toLong()
        override fun readFile(relDir: String, name: String): ByteArray? { downloads++; return files["$relDir/$name"] }
        override fun uploadDir(localDir: File, relDir: String) {}
    }

    @Test fun usesMirrorOnlyWhenSizeMatches() {
        val same = "[1]".toByteArray()
        val remote = Fake(mapOf("chat/2026-11-01/$JSON_NAME" to same, "chat/2026-11-02/$JSON_NAME" to "[1,2]".toByteArray()))
        val mirror = Mirror(tmp.root)
        mirror.save("chat", "2026-11-01", same)            // مطابق
        mirror.save("chat", "2026-11-02", "[9]".toByteArray()) // حجم مختلف
        val up = MirroringUploader(remote, mirror)
        assertArrayEquals(same, up.readFile("chat/2026-11-01", JSON_NAME))
        assertEquals("لا تنزيل عند التطابق", 0, remote.downloads)
        assertArrayEquals("[1,2]".toByteArray(), up.readFile("chat/2026-11-02", JSON_NAME))
        assertEquals("تنزيل عند الاختلاف", 1, remote.downloads)
        assertNull("غير موجود في الوجهة: يوم جديد", up.readFile("chat/2026-11-03", JSON_NAME))
        assertEquals(1, remote.downloads)
    }
}
