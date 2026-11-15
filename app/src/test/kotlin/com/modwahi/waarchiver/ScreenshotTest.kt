package com.modwahi.waarchiver

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.modwahi.waarchiver.data.ChatChoice
import com.modwahi.waarchiver.data.KnownChat
import com.modwahi.waarchiver.data.Prefs
import com.modwahi.waarchiver.ui.Actions
import com.modwahi.waarchiver.ui.AppTheme
import com.modwahi.waarchiver.ui.AppViewModel
import com.modwahi.waarchiver.ui.Root
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** لقطات شاشة للمراجعة البصرية في مجلد build/screens. ليست اختبار مطابقة. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val actions = object : Actions {
        override fun pickWhatsAppFolder() {}
        override fun pickLocalFolder() {}
        override fun signInGoogle() {}
        override fun exportLogs() {}
        override fun requestBatteryExemption() {}
        override fun requestNotifications() {}
        override fun requestLegacyStorage() {}
        override fun openUrl(url: String) {}
        override fun requestContacts() {}
        override fun openDownloads() {}
        override fun openAppSettings() {}
        override fun openInstallPermission() {}
    }

    private fun seed(setupDone: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val p = Prefs(app)
        p.setupDone = setupDone
        p.driveAccount = "modwahi@gmail.com"
        p.driveFolderId = "abc"
        val now = System.currentTimeMillis() / 1000
        p.knownChats = listOf(
            KnownChat("120363111111111111@g.us", "طلبات التحويل: الشركة", true, 18234, now - 3600),
            KnownChat("967773333333@s.whatsapp.net", "", false, 412, now - 7200),
            KnownChat("120363999999999999@g.us", "العائلة", true, 9520, now - 86400),
            KnownChat("967771111111@s.whatsapp.net", "", false, 77, now - 3 * 86400),
            KnownChat("120363222222222222@g.us", "مشتريات المحل", true, 2211, now - 5 * 86400),
            KnownChat("145479585235031@lid", "", false, 8, now - 6 * 86400, "967772222222", "سالم المحاسب"),
            KnownChat("127097158803600@lid", "", false, 25, now - 6 * 86400, "967771111111", ""),
        )
        p.knownChatsAt = System.currentTimeMillis() - 3 * 3600_000
        p.choices = listOf(ChatChoice("120363111111111111@g.us", true), ChatChoice("967773333333@s.whatsapp.net", true, "العميل سعيد"))
        p.contacts = mapOf("967771111111" to "أحمد")
        val run = JSONObject().put("time", System.currentTimeMillis() - 5 * 3600_000).put("ok", true).put("dry", false)
            .put("scheduled", true).put("text", "=== تشغيل 2026-11-02 04:00:00 ===\nعمر ملف النسخة الاحتياطية: 2.0 ساعة\n✓ طلبات التحويل_ الشركة: رسائل 7 | وسائط 2\nالمجموع: 9 رسالة، 2 وسيط. الحالة: ناجح\n")
            .put("errors", JSONArray()).put("warnings", JSONArray()).put("exported", JSONObject.NULL)
            .put("chats", JSONArray().put(JSONObject().put("name", "طلبات التحويل_ الشركة").put("messages", 7).put("media", 2).put("missing", 1).put("large", 0).put("recovered", 0).put("error", JSONObject.NULL))
                .put(JSONObject().put("name", "العميل سعيد").put("messages", 2).put("media", 0).put("missing", 0).put("large", 0).put("recovered", 0).put("error", JSONObject.NULL)))
        p.lastRun = run.toString()
        p.lastAuto = JSONObject().put("time", System.currentTimeMillis() - 40 * 60_000).put("tone", "info")
            .put("text", "لا توجد نسخة احتياطية جديدة منذ آخر أرشفة")
        p.history = listOf(run, JSONObject(run.toString()).put("time", run.getLong("time") - 86400_000).put("ok", false)
            .put("errors", JSONArray().put("انقطع الاتصال بـ Google Drive")))
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(dark: Int = 0, job: com.modwahi.waarchiver.ui.Job? = null) {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext())
        vm.previewJob(job)
        vm.updateTheme(dark)
        rule.setContent {
            AppTheme(vm.themeMode) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Root(vm, actions) }
            }
        }
    }

    @Test fun main() {
        seed(true)
        render()
        shot("1_home")
        rule.onNodeWithText("المحادثات").performClick()
        shot("2_chats")
        rule.onNodeWithText("الإعدادات").performClick()
        shot("3_settings")
        rule.onNodeWithText("سجل التشغيلات").performScrollTo().performClick()
        shot("4_history")
    }

    @Test fun uploading() {
        seed(true)
        render(job = com.modwahi.waarchiver.ui.Job("run", "رفع: طلبات التحويل_ الشركة", 71, 3,
            done = 38_400_000, total = 87_200_000, speed = 1_250_000, eta = 39))
        shot("12_uploading")
    }

    @Test fun updateAvailable() {
        seed(true)
        val vm = AppViewModel(ApplicationProvider.getApplicationContext())
        vm.previewUpdate(com.modwahi.waarchiver.io.Updates.Release("1.4.0", "", "", "whatsapp-archiver-v1.4.0.apk", 2_600_000, ""))
        rule.setContent {
            AppTheme(0) { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Root(vm, actions) } }
        }
        shot("13_update")
    }

    @Test fun dark() {
        seed(true)
        render(2)
        shot("5_home_dark")
    }

    @Test fun setup() {
        seed(false)
        render()
        shot("6_setup_welcome")
        rule.onNodeWithText("التالي").performClick()
        shot("7_setup_folder")
        rule.onNodeWithText("التالي").performClick()
        shot("8_setup_key")
    }

    @Test fun reader() {
        seed(true)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val src = File("../core/src/test/resources/fixtures/full_sequence/step3/expected/remote")
        src.walkTopDown().filter { it.name == "الرسائل.json" }.forEach { f ->
            val rel = f.relativeTo(src).path
            File(app.filesDir, "mirror/$rel").apply { parentFile!!.mkdirs(); writeBytes(f.readBytes()) }
        }
        render()
        rule.onNodeWithText("الرسائل").performClick()
        shot("10_reader_list")
        rule.onNodeWithText("طلبات التحويل_ الشركة").performClick()
        shot("11_reader_chat")
    }

    @Test fun chatEdit() {
        seed(true)
        render()
        rule.onNodeWithText("المحادثات").performClick()
        rule.onAllNodesWithContentDescription("تعديل")[0].performClick()
        shot("9_chat_edit")
    }
}
