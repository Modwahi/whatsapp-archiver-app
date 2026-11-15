package com.modwahi.waarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

/**
 * يشغّل نفس السيناريوهات التي شغّلتها أداة Python (tools/gen_fixtures.py) على نفس الملفات المشفرة،
 * ويتحقق أن شجرة المخرجات (المجلدات، الرسائل.txt، الرسائل.json، الوسائط، _سجل التشغيل) مطابقة بايتاً ببايت.
 */
class PythonParityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fixtures = File("src/test/resources/fixtures")

    /** رسائل خطأ خاصة بالتطبيق تختلف نصاً عن أداة Termux (لأنها تشير لأوامر Termux). */
    private val APP_TEXT = mapOf(
        "المحادثة غير موجودة في النسخة الاحتياطية (تحقق من الـ jid عبر list-chats)" to
            "المحادثة غير موجودة في النسخة الاحتياطية (ربما حُذفت أو غُيّرت)",
    )

    private fun appText(s: String): String = APP_TEXT.entries.fold(s) { acc, (py, app) -> acc.replace(py, app) }

    class FailFor(base: File, private val bad: String) : Uploader {
        private val inner = LocalUploader(base)
        override fun readFile(relDir: String, name: String) = inner.readFile(relDir, name)
        override fun uploadDir(localDir: File, relDir: String) {
            if (relDir == bad) throw UploadError("انقطع الاتصال")
            inner.uploadDir(localDir, relDir)
        }
    }

    private fun tree(root: File): Map<String, ByteArray> =
        if (!root.exists()) emptyMap() else root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).path to it.readBytes() }

    private fun assertSameTree(expected: File, actual: File, ctx: String, transform: (String, ByteArray) -> ByteArray = { _, b -> b }) {
        val e = tree(expected).mapValues { (k, v) -> transform(k, v) }
        val a = tree(actual)
        assertEquals("$ctx: أسماء الملفات", e.keys.sorted(), a.keys.sorted())
        for ((k, v) in e) {
            val av = a.getValue(k)
            if (!v.contentEquals(av)) {
                assertEquals("$ctx: محتوى $k", String(v, Charsets.UTF_8), String(av, Charsets.UTF_8))
                assertTrue("$ctx: بايتات $k", v.contentEquals(av))
            }
        }
    }

    private fun copyDir(from: File, to: File) {
        to.deleteRecursively()
        to.mkdirs()
        if (from.exists()) from.copyRecursively(to, overwrite = true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun runScenario(name: String) {
        val dir = File(fixtures, name)
        val root = tmp.newFolder(name)
        val wa = File(root, "WhatsApp")
        val remote = File(root, "drive")
        val paths = Paths(File(root, "home"))
        val steps = dir.listFiles()!!.filter { it.name.startsWith("step") }.sortedBy { it.name.removePrefix("step").toInt() }
        assertTrue(steps.isNotEmpty())
        for (sd in steps) {
            val ctx = "$name/${sd.name}"
            val step = Json.parse(File(sd, "step.json").readText()) as JObj
            val c = step["config"] as JObj
            val cfg = ArchiveConfig(
                chats = (c["chats"] as List<JObj>).map { ChatSpec(it["jid"] as String, it["name"] as String) },
                firstRunDays = c["first_run_days"].asLong().toInt(),
                maxMediaMb = c["max_media_mb"].asLong().toInt(),
                staleBackupHours = c["stale_backup_hours"].asLong().toInt(),
                meName = c["me_name"] as String,
                zone = java.time.ZoneId.of(c["timezone"] as String),
            )
            val contacts = (c["contacts"] as JObj).mapValues { it.value as String }
            // مجلد واتساب كما كان وقت هذه الخطوة
            copyDir(File(sd, "input/wa"), wa)
            val enc = File(sd, "input/msgstore.db.crypt15")
            if (enc.exists()) {
                val target = File(wa, "Databases/msgstore.db.crypt15")
                target.parentFile.mkdirs()
                enc.copyTo(target, overwrite = true)
                target.setLastModified(Math.round(step["backup_mtime"].asDouble() * 1000))
            }
            if (step["state_before_exists"] == false) paths.state.delete() // كما حذفته خطوة Python
            val waBefore = tree(wa)
            val dry = step["dry_run"] == true
            val dryDir = File(root, "dry_${sd.name}")
            val uploader: Uploader = when {
                dry -> LocalUploader(File(dryDir, ".no-remote"))
                step["fail_upload"] != null -> FailFor(remote, step["fail_upload"] as String)
                else -> LocalUploader(remote)
            }
            val s = runArchive(
                cfg, paths, step["key"] as String, FileSourceFs(wa), uploader, NullLog, JdbcOpener,
                contacts = contacts, dryRun = dry, scheduled = step["scheduled"] == true,
                dryDir = if (dry) dryDir else null, now = step["now"].asDouble(),
            )
            val exp = File(sd, "expected")
            val result = step["result"] as JObj?
            if (result == null) {
                assertNull("$ctx: يجب أن يُتخطى التشغيل", s)
            } else {
                s!!
                assertEquals("$ctx: الملخص", appText(File(exp, "summary.txt").readText()), s.render())
                assertEquals("$ctx: ok", result["ok"], s.ok)
                assertEquals("$ctx: errors", result["errors"], s.errors)
                assertEquals("$ctx: warnings", result["warnings"], s.warnings)
                val chats = (result["chats"] as List<JObj>)
                assertEquals(chats.size, s.chats.size)
                chats.zip(s.chats).forEach { (e, a) ->
                    assertEquals("$ctx: ${e["jid"]}", listOf(e["jid"], e["name"], e["messages"], e["media"], e["media_missing"],
                        e["media_too_large"], e["recovered"], e["error"]?.let { appText(it as String) }),
                        listOf(a.jid, a.name, a.messages.toLong(), a.media.toLong(), a.mediaMissing.toLong(),
                            a.mediaTooLarge.toLong(), a.recovered.toLong(), a.error))
                }
            }
            assertSameTree(File(exp, "remote"), remote, "$ctx: الدرايف") { k, b ->
                if (k.endsWith(".txt")) appText(String(b, Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else b
            }
            if (dry) assertSameTree(File(exp, "dry"), dryDir, "$ctx: التجربة")

            // الحالة: نفس القيم (last_backup_mtime رقم عشري قد يختلف تمثيله فقط)
            val expState = step["state"] as JObj?
            if (expState == null) assertFalse("$ctx: لا حالة", paths.state.exists())
            else {
                val act = Json.parse(paths.state.readText()) as JObj
                assertEquals("$ctx: الحالة", normalizeState(expState), normalizeState(act))
            }
            // القاعدة المفكوكة حُذفت، وملفات واتساب لم تُلمس
            assertFalse("$ctx: القاعدة المفكوكة حُذفت", paths.tmp.walkTopDown().any { it.name.startsWith("msgstore.db") })
            assertEquals("$ctx: ملفات واتساب لم تتغير", waBefore.mapValues { it.value.toList() }, tree(wa).mapValues { it.value.toList() })
        }
    }

    private fun normalizeState(s: JObj): String {
        val copy = Json.parse(Json.dumps(s)) as JObj
        copy["last_backup_mtime"]?.let { copy["last_backup_mtime"] = it.asDouble() }
        return Json.dumps(copy)
    }

    object NullLog : ArchLog {
        val lines = mutableListOf<String>()
        override fun info(msg: String) { lines.add(msg) }
        override fun warning(msg: String) { lines.add(msg) }
        override fun error(msg: String) { lines.add(msg) }
    }

    @Test fun fullSequence() = runScenario("full_sequence")
    @Test fun stateLost() = runScenario("state_lost")
    @Test fun staleAndLarge() = runScenario("stale_and_large")
    @Test fun dryRun() = runScenario("dry_run")
    @Test fun uploadFailure() = runScenario("upload_failure")
    @Test fun wrongKey() = runScenario("wrong_key")
    @Test fun unknownChat() = runScenario("unknown_chat")

    @Test
    fun summaryStartedUsesZone() {
        val s = Summary(LocalDateTime.of(2026, 11, 2, 4, 0))
        assertTrue(s.render().startsWith("=== تشغيل 2026-11-02 04:00:00 ==="))
    }
}

/** الشريط الكلي: يتقدم من 0 إلى 1 بلا تراجع، والحجم المرفوع يساوي حجم الملفات فعلاً. */
class ProgressTest {
    @get:Rule val tmp = TemporaryFolder()

    @Suppress("UNCHECKED_CAST")
    @Test fun overallProgressMonotonicAndExactBytes() {
        val sd = File("src/test/resources/fixtures/full_sequence/step1")
        val step = Json.parse(File(sd, "step.json").readText()) as JObj
        val c = step["config"] as JObj
        val wa = tmp.newFolder("WhatsApp")
        File(sd, "input/wa").copyRecursively(wa)
        File(wa, "Databases").mkdirs()
        File(sd, "input/msgstore.db.crypt15").copyTo(File(wa, "Databases/msgstore.db.crypt15"))
        val remote = tmp.newFolder("drive")
        val events = mutableListOf<RunProgress>()
        val s = runArchive(
            ArchiveConfig(chats = (c["chats"] as List<JObj>).map { ChatSpec(it["jid"] as String, it["name"] as String) },
                maxMediaMb = 1, zone = java.time.ZoneId.of("Asia/Aden")),
            Paths(tmp.newFolder("home")), step["key"] as String, FileSourceFs(wa), LocalUploader(remote),
            PythonParityTest.NullLog, JdbcOpener, contacts = mapOf("967771111111" to "أحمد"), now = step["now"].asDouble(),
            onProgress = { events.add(it) })!!
        assertTrue(s.ok)
        val overall = events.map { it.overall }
        assertEquals(overall, overall.sorted())
        assertEquals(1.0, overall.last(), 0.0)
        assertEquals(listOf(1, 2, 3), events.map { it.phase }.distinct())
        val chatBytes = remote.walkTopDown().filter { it.isFile && !it.path.contains(LOG_DIR_REMOTE) }.sumOf { it.length() }
        assertEquals(chatBytes, events.last().bytesTotal)
        assertEquals(chatBytes, events.last().bytesDone)
    }
}
