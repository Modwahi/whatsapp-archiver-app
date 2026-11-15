package com.modwahi.waarchiver.data

import android.content.Context
import android.content.SharedPreferences
import com.modwahi.waarchiver.core.ArchiveConfig
import com.modwahi.waarchiver.core.ChatSpec
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/** محادثة كما ظهرت في آخر قراءة للنسخة (للعرض فقط). */
data class KnownChat(
    val jid: String,
    val subject: String,
    val isGroup: Boolean,
    val count: Long,
    val lastTs: Long,
    /** الرقم الحقيقي (حتى لمحادثات @lid) */
    val phone: String = "",
    /** الاسم الذي وضعه الشخص لنفسه */
    val pushName: String = "",
)

/** إعدادات محادثة مختارة: اسم المجلد (اختياري) واسم صاحب الرقم للمحادثات الخاصة. */
data class ChatChoice(val jid: String, val selected: Boolean, val folder: String = "")

enum class Mode { DRIVE, LOCAL }

const val DRIVE_FOLDER_NAME = "دردشات الواتساب (التطبيق)"

/** كل الإعدادات (ما عدا المفتاح، وهو في KeyVault). */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(k: String) = sp.getString(k, null)
    private fun put(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    var waTreeUri: String?
        get() = str("wa_tree")
        set(v) = put { putString("wa_tree", v) }

    var mode: Mode
        get() = if (str("mode") == "local") Mode.LOCAL else Mode.DRIVE
        set(v) = put { putString("mode", if (v == Mode.LOCAL) "local" else "drive") }

    var localTreeUri: String?
        get() = str("local_tree")
        set(v) = put { putString("local_tree", v) }

    var driveAccount: String?
        get() = str("drive_account")
        set(v) = put { putString("drive_account", v) }

    /** نحفظ الـ ID لا المسار: يمكن نقل المجلد يدوياً في الدرايف ويستمر التطبيق بالكتابة فيه. */
    var driveFolderId: String?
        get() = str("drive_folder_id")
        set(v) = put { putString("drive_folder_id", v) }

    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(v) = put { putBoolean("setup_done", v) }

    var firstRunDays: Int
        get() = sp.getInt("first_run_days", 7)
        set(v) = put { putInt("first_run_days", v) }

    var maxMediaMb: Int
        get() = sp.getInt("max_media_mb", 100)
        set(v) = put { putInt("max_media_mb", v) }

    var staleBackupHours: Int
        get() = sp.getInt("stale_hours", 36)
        set(v) = put { putInt("stale_hours", v) }

    var timezone: String
        get() = str("timezone") ?: "Asia/Aden"
        set(v) = put { putString("timezone", v) }

    var meName: String
        get() = str("me_name") ?: "أنا"
        set(v) = put { putString("me_name", v) }

    var requireCharging: Boolean
        get() = sp.getBoolean("req_charging", true)
        set(v) = put { putBoolean("req_charging", v) }

    var requireWifi: Boolean
        get() = sp.getBoolean("req_wifi", true)
        set(v) = put { putBoolean("req_wifi", v) }

    var scheduleEnabled: Boolean
        get() = sp.getBoolean("schedule_on", true)
        set(v) = put { putBoolean("schedule_on", v) }

    /** آخر ملخص تشغيل (JSON) */
    var lastRun: String?
        get() = str("last_run")
        set(v) = put { putString("last_run", v) }

    /** آخر 30 تشغيلاً (JSON array، الأحدث أولاً) */
    var history: List<JSONObject>
        get() = str("history")?.let { s -> val a = JSONArray(s); (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        set(v) = put { putString("history", JSONArray(v.take(30)).toString()) }

    /** 0 حسب النظام، 1 فاتح، 2 داكن */
    var themeMode: Int
        get() = sp.getInt("theme", 0)
        set(v) = put { putInt("theme", v) }

    var notifySuccess: Boolean
        get() = sp.getBoolean("notify_success", false)
        set(v) = put { putBoolean("notify_success", v) }

    /** استخدام أسماء جهات الاتصال في التلفون لأسماء المرسلين (مثل contacts.json في الأداة) */
    var importContacts: Boolean
        get() = sp.getBoolean("import_contacts", false)
        set(v) = put { putBoolean("import_contacts", v) }

    /** رمز الدولة لتحويل الأرقام المحلية (077…) في جهات الاتصال إلى دولية (96777…) */
    var countryCode: String
        get() = str("country_code") ?: "967"
        set(v) = put { putString("country_code", v) }

    /** فشل التشغيل المجدول لنفس النسخة (للانتظار المتزايد قبل إعادة المحاولة) */
    var failState: com.modwahi.waarchiver.work.AutoPolicy.FailState?
        get() = str("fail_state")?.let { runCatching {
            val o = JSONObject(it)
            com.modwahi.waarchiver.work.AutoPolicy.FailState(o.getLong("m"), o.getInt("c"), o.getLong("t"))
        }.getOrNull() }
        set(v) = put { putString("fail_state", v?.let { JSONObject().put("m", it.backupMtimeMs).put("c", it.count).put("t", it.lastAtMs).toString() }) }

    /** آخر فحص تلقائي: {"time":ms, "tone":"ok|warn|error|info", "text":"..."} */
    var lastAuto: JSONObject?
        get() = str("last_auto")?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(v) = put { putString("last_auto", v?.toString()) }

    var bgDeniedNotifiedDay: String?
        get() = str("bg_denied_day")
        set(v) = put { putString("bg_denied_day", v) }

    var staleNotifiedDay: String?
        get() = str("stale_notified")
        set(v) = put { putString("stale_notified", v) }

    var knownChats: List<KnownChat>
        get() = str("known_chats")?.let { s ->
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                KnownChat(o.getString("jid"), o.optString("subject"), o.optBoolean("group"), o.optLong("count"), o.optLong("last"),
                    o.optString("phone"), o.optString("push"))
            }
        } ?: emptyList()
        set(v) = put {
            putString("known_chats", JSONArray(v.map {
                JSONObject().put("jid", it.jid).put("subject", it.subject).put("group", it.isGroup)
                    .put("count", it.count).put("last", it.lastTs).put("phone", it.phone).put("push", it.pushName)
            }).toString())
        }

    var knownChatsAt: Long
        get() = sp.getLong("known_chats_at", 0)
        set(v) = put { putLong("known_chats_at", v) }

    var choices: List<ChatChoice>
        get() = str("choices")?.let { s ->
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                ChatChoice(o.getString("jid"), o.optBoolean("selected"), o.optString("folder"))
            }
        } ?: emptyList()
        set(v) = put {
            putString("choices", JSONArray(v.map {
                JSONObject().put("jid", it.jid).put("selected", it.selected).put("folder", it.folder)
            }).toString())
        }

    /** أسماء أصحاب الأرقام: {"967771234567": "أحمد"} — مثل contacts.json في الأداة */
    var contacts: Map<String, String>
        get() = str("contacts")?.let { s ->
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { o.getString(it) }
        } ?: emptyMap()
        set(v) = put { putString("contacts", JSONObject(v.filterValues { it.isNotBlank() }).toString()) }

    fun archiveConfig(): ArchiveConfig = ArchiveConfig(
        chats = choices.filter { it.selected }.map { ChatSpec(it.jid, it.folder.trim()) },
        firstRunDays = firstRunDays,
        maxMediaMb = maxMediaMb,
        staleBackupHours = staleBackupHours,
        meName = meName,
        zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("Asia/Aden")),
    )
}
