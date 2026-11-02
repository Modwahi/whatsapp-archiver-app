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

    var staleNotifiedDay: String?
        get() = str("stale_notified")
        set(v) = put { putString("stale_notified", v) }

    var knownChats: List<KnownChat>
        get() = str("known_chats")?.let { s ->
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                KnownChat(o.getString("jid"), o.optString("subject"), o.optBoolean("group"), o.optLong("count"), o.optLong("last"))
            }
        } ?: emptyList()
        set(v) = put {
            putString("known_chats", JSONArray(v.map {
                JSONObject().put("jid", it.jid).put("subject", it.subject).put("group", it.isGroup)
                    .put("count", it.count).put("last", it.lastTs)
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
