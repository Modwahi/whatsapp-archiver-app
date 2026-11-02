package com.modwahi.waarchiver.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.modwahi.waarchiver.core.Archiver
import com.modwahi.waarchiver.core.Crypt15
import com.modwahi.waarchiver.core.Naming
import com.modwahi.waarchiver.data.ChatChoice
import com.modwahi.waarchiver.data.KnownChat
import com.modwahi.waarchiver.data.Mode
import com.modwahi.waarchiver.io.SafSource
import com.modwahi.waarchiver.work.ArchiveWorker
import com.modwahi.waarchiver.work.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Job(val action: String, val stage: String, val pct: Int)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val engine = Engine(app)
    private val prefs get() = engine.prefs

    var setupDone by mutableStateOf(prefs.setupDone); private set
    var waUri by mutableStateOf(prefs.waTreeUri); private set
    var hasKey by mutableStateOf(engine.vault.hasKey); private set
    var mode by mutableStateOf(prefs.mode); private set
    var account by mutableStateOf(prefs.driveAccount); private set
    var localUri by mutableStateOf(prefs.localTreeUri); private set
    var driveFolderId by mutableStateOf(prefs.driveFolderId); private set
    var known by mutableStateOf(prefs.knownChats); private set
    var knownAt by mutableStateOf(prefs.knownChatsAt); private set
    var choices by mutableStateOf(prefs.choices.associateBy { it.jid }); private set
    var contacts by mutableStateOf(prefs.contacts); private set
    var lastRun by mutableStateOf(prefs.lastRun?.let { JSONObject(it) }); private set
    var backupAge by mutableStateOf<Double?>(null); private set
    var job by mutableStateOf<Job?>(null); private set
    var listError by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    /** نص آخر انهيار (إن وُجد) لعرضه ونسخه */
    var crash by mutableStateOf<String?>(com.modwahi.waarchiver.App.crashFile(app).takeIf { it.exists() }?.readText())

    fun dismissCrash() {
        com.modwahi.waarchiver.App.crashFile(getApplication()).delete()
        crash = null
    }

    // الإعدادات
    var firstRunDays by mutableStateOf(prefs.firstRunDays); private set
    var maxMediaMb by mutableStateOf(prefs.maxMediaMb); private set
    var timezone by mutableStateOf(prefs.timezone); private set
    var meName by mutableStateOf(prefs.meName); private set
    var requireCharging by mutableStateOf(prefs.requireCharging); private set
    var requireWifi by mutableStateOf(prefs.requireWifi); private set
    var scheduleEnabled by mutableStateOf(prefs.scheduleEnabled); private set

    init {
        refreshAge()
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ArchiveWorker.MANUAL).collectLatest { infos ->
                onWork(infos)
            }
        }
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ArchiveWorker.PERIODIC).collectLatest { infos ->
                if (job == null || job?.action == "scheduled") {
                    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    job = running?.let { Job("scheduled", it.progress.getString("stage") ?: "تشغيل مجدول…", it.progress.getInt("pct", -1)) }
                    if (running == null) reload()
                }
            }
        }
    }

    private fun onWork(infos: List<WorkInfo>) {
        val active = infos.firstOrNull { !it.state.isFinished }
        if (active != null) {
            val action = active.tags.firstOrNull { it in setOf("run", "dry", "list") } ?: "run"
            job = Job(action, active.progress.getString("stage") ?: "في الانتظار…", active.progress.getInt("pct", -1))
            return
        }
        val wasList = job?.action == "list"
        job = null
        val done = infos.lastOrNull()
        if (wasList && done?.state == WorkInfo.State.FAILED) listError = done.outputData.getString("error")
        reload()
    }

    fun reload() {
        known = prefs.knownChats
        knownAt = prefs.knownChatsAt
        lastRun = prefs.lastRun?.let { JSONObject(it) }
        driveFolderId = prefs.driveFolderId
        refreshAge()
    }

    fun refreshAge() {
        viewModelScope.launch { backupAge = withContext(Dispatchers.IO) { engine.backupAgeHours() } }
    }

    // ---------- مجلد واتساب ----------
    /** يحفظ صلاحية القراءة فقط بشكل دائم. يرجع رسالة خطأ أو null. */
    suspend fun setWaFolder(uri: Uri): String? = withContext(Dispatchers.IO) {
        val cr = getApplication<Application>().contentResolver
        runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val src = SafSource(getApplication(), uri)
        if (!src.looksValid()) return@withContext "هذا ليس مجلد واتساب. اختر: Android ← media ← com.whatsapp ← WhatsApp"
        prefs.waTreeUri = uri.toString()
        waUri = uri.toString()
        val b = src.backup()
        refreshAge()
        if (b == null) "اخترت المجلد، لكن لم أجد ملف النسخة Databases/msgstore.db.crypt15. فعّل النسخ الاحتياطي المشفّر في واتساب ثم اضغط «نسخ احتياطي»."
        else null
    }

    // ---------- المفتاح ----------
    suspend fun verifyAndSaveKey(raw: String): String? = withContext(Dispatchers.IO) {
        val key = Crypt15.cleanKey(raw)
        if (!Crypt15.isValidKey(key)) return@withContext "المفتاح يجب أن يكون 64 خانة من 0-9 و a-f (عددها الآن ${key.length})"
        try {
            engine.verifyKey(key)
        } catch (e: Exception) {
            return@withContext (e.message ?: "فشل الفك التجريبي") + "\nلم أحفظ المفتاح."
        }
        engine.vault.save(key)
        hasKey = true
        null
    }

    // ---------- الوجهة ----------
    fun chooseAccount(email: String) {
        prefs.driveAccount = email
        account = email
        useMode(Mode.DRIVE)
    }

    /** يتأكد من الاتصال بالدرايف وينشئ مجلد التطبيق إن لم يوجد. */
    suspend fun checkDrive(): String? = withContext(Dispatchers.IO) {
        try {
            val d = engine.driveClient()
            engine.ensureDriveFolder(d)
            driveFolderId = prefs.driveFolderId
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    fun setLocalFolder(uri: Uri) {
        val cr = getApplication<Application>().contentResolver
        runCatching {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        prefs.localTreeUri = uri.toString()
        localUri = uri.toString()
        useMode(Mode.LOCAL)
    }

    fun useMode(m: Mode) {
        prefs.mode = m
        mode = m
        ArchiveWorker.schedule(getApplication())
    }

    // ---------- المحادثات ----------
    fun refreshChats() {
        listError = null
        ArchiveWorker.enqueue(getApplication(), "list")
    }

    private fun saveChoices(m: Map<String, ChatChoice>) {
        choices = m
        prefs.choices = m.values.toList()
    }

    fun toggle(jid: String, on: Boolean) {
        val c = choices[jid] ?: ChatChoice(jid, false)
        saveChoices(choices + (jid to c.copy(selected = on)))
    }

    fun setFolder(jid: String, folder: String) {
        val c = choices[jid] ?: ChatChoice(jid, true)
        saveChoices(choices + (jid to c.copy(folder = folder)))
    }

    fun setOwnerName(jid: String, name: String) {
        val num = Archiver.numberOf(jid)
        if (num.isEmpty()) return
        val m = contacts.toMutableMap()
        if (name.isBlank()) m.remove(num) else m[num] = name
        contacts = m
        prefs.contacts = m
    }

    fun displayName(c: KnownChat): String {
        if (c.subject.isNotEmpty()) return c.subject
        val num = Archiver.numberOf(c.jid)
        return contacts[num]?.takeIf { it.isNotBlank() } ?: if (num.isNotEmpty()) "+$num" else c.jid.substringBefore('@')
    }

    /** اسم المجلد الافتراضي كما ستسميه الأداة إن تُرك الحقل فارغاً. */
    fun defaultFolder(c: KnownChat): String {
        val name = c.subject.ifEmpty {
            val num = Archiver.numberOf(c.jid)
            contacts[num]?.takeIf { it.isNotEmpty() } ?: num.ifEmpty { c.jid }
        }
        return Naming.sanitizeName(name)
    }

    val selectedCount get() = choices.values.count { it.selected }

    // ---------- التشغيل ----------
    fun runNow() = ArchiveWorker.enqueue(getApplication(), "run")
    fun dryRun() = ArchiveWorker.enqueue(getApplication(), "dry")

    fun finishSetup() {
        prefs.setupDone = true
        setupDone = true
        ArchiveWorker.schedule(getApplication())
    }

    // ---------- الإعدادات ----------
    fun updateSchedule(enabled: Boolean = scheduleEnabled, charging: Boolean = requireCharging, wifi: Boolean = requireWifi) {
        prefs.scheduleEnabled = enabled; scheduleEnabled = enabled
        prefs.requireCharging = charging; requireCharging = charging
        prefs.requireWifi = wifi; requireWifi = wifi
        ArchiveWorker.schedule(getApplication())
    }

    fun updateNumbers(days: Int? = null, maxMb: Int? = null) {
        days?.takeIf { it > 0 }?.let { prefs.firstRunDays = it; firstRunDays = it }
        maxMb?.takeIf { it > 0 }?.let { prefs.maxMediaMb = it; maxMediaMb = it }
    }

    fun updateTimezone(tz: String): Boolean {
        val ok = runCatching { java.time.ZoneId.of(tz.trim()) }.isSuccess
        if (ok) { prefs.timezone = tz.trim(); timezone = tz.trim() }
        return ok
    }

    fun updateMeName(n: String) {
        if (n.isNotBlank()) { prefs.meName = n.trim(); meName = n.trim() }
    }

    suspend fun exportLogs(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write(engine.allLogs().toByteArray(Charsets.UTF_8))
            }
            null
        } catch (e: Exception) {
            e.message
        }
    }
}
