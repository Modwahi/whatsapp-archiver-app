package com.modwahi.waarchiver.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.modwahi.waarchiver.App
import com.modwahi.waarchiver.core.Archiver
import com.modwahi.waarchiver.core.Crypt15
import com.modwahi.waarchiver.core.Naming
import com.modwahi.waarchiver.data.ChatChoice
import com.modwahi.waarchiver.data.KnownChat
import com.modwahi.waarchiver.data.Mode
import com.modwahi.waarchiver.io.PhoneContacts
import com.modwahi.waarchiver.io.SafSource
import com.modwahi.waarchiver.work.ArchiveWorker
import com.modwahi.waarchiver.work.ChatProgress
import com.modwahi.waarchiver.work.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Job(
    val action: String, val stage: String, val pct: Int,
    val phase: Int = 0, val done: Long = 0, val total: Long = 0, val speed: Long = 0, val eta: Long = -1,
) {
    companion object {
        fun from(action: String, d: androidx.work.Data, fallback: String) = Job(action, d.getString("stage") ?: fallback,
            d.getInt("pct", -1), d.getInt("phase", 0), d.getLong("done", 0), d.getLong("total", 0),
            d.getLong("speed", 0), d.getLong("eta", -1))
    }
}

enum class SortBy { RECENT, NAME, MESSAGES }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val engine = Engine(app)
    private val prefs get() = engine.prefs
    private val wm = WorkManager.getInstance(app)

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
    var lastAuto by mutableStateOf(prefs.lastAuto); private set
    var history by mutableStateOf(prefs.history); private set
    var progressByChat by mutableStateOf<Map<String, ChatProgress>>(emptyMap()); private set
    var backupAge by mutableStateOf<Double?>(null); private set
    var job by mutableStateOf<Job?>(null); private set
    var nextCheckAt by mutableStateOf<Long?>(null); private set
    var batteryExempt by mutableStateOf(true); private set
    var phoneContactsCount by mutableStateOf(0); private set
    /** أسماء جهات الاتصال في التلفون (إن فُعّلت) */
    var phoneContacts by mutableStateOf<Map<String, String>>(emptyMap()); private set
    /** المحادثات المتوفرة للقراءة داخل التطبيق */
    var readable by mutableStateOf<List<com.modwahi.waarchiver.work.Mirror.ChatDir>>(emptyList()); private set
    // التحديثات
    var update by mutableStateOf<com.modwahi.waarchiver.io.Updates.Release?>(null); private set
    /** null لا شيء، "checking"، "downloading"، "installing" */
    var updateState by mutableStateOf<String?>(null); private set
    var updatePct by mutableStateOf(0f); private set
    val currentVersion: String = com.modwahi.waarchiver.BuildConfig.VERSION_NAME
    var listError by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var crash by mutableStateOf(App.crashFile(app).takeIf { it.exists() }?.readText())

    // الإعدادات
    var firstRunDays by mutableStateOf(prefs.firstRunDays); private set
    var maxMediaMb by mutableStateOf(prefs.maxMediaMb); private set
    var timezone by mutableStateOf(prefs.timezone); private set
    var meName by mutableStateOf(prefs.meName); private set
    var requireCharging by mutableStateOf(prefs.requireCharging); private set
    var requireWifi by mutableStateOf(prefs.requireWifi); private set
    var scheduleEnabled by mutableStateOf(prefs.scheduleEnabled); private set
    var themeMode by mutableStateOf(prefs.themeMode); private set
    var notifySuccess by mutableStateOf(prefs.notifySuccess); private set
    var importContacts by mutableStateOf(prefs.importContacts); private set
    var countryCode by mutableStateOf(prefs.countryCode); private set

    init {
        Sounds.warmUp(app)
        reload()
        if (System.currentTimeMillis() - prefs.lastUpdateCheck > 6 * 3_600_000L) checkUpdates(manual = false)
        viewModelScope.launch {
            wm.getWorkInfosForUniqueWorkFlow(ArchiveWorker.MANUAL).collectLatest { onManual(it) }
        }
        viewModelScope.launch {
            wm.getWorkInfosForUniqueWorkFlow(ArchiveWorker.PERIODIC).collectLatest { infos ->
                val p = infos.firstOrNull { !it.state.isFinished }
                nextCheckAt = p?.nextScheduleTimeMillis?.takeIf { it > 0 && it != Long.MAX_VALUE }
                if (job == null || job?.action == "scheduled") {
                    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    job = running?.let { Job.from("scheduled", it.progress, "تشغيل مجدول…") }
                    if (running == null) reload()
                }
            }
        }
    }

    private var jobStartedAt = 0L

    private fun onManual(infos: List<WorkInfo>) {
        val active = infos.firstOrNull { !it.state.isFinished }
        if (active != null) {
            val action = active.tags.firstOrNull { it in setOf("run", "dry", "list", "mirror") } ?: "run"
            if (job == null) {
                jobStartedAt = System.currentTimeMillis()
                if (action == "run" || action == "dry") Sounds.play(getApplication(), Sounds.Snd.START, 0.5f)
            }
            job = Job.from(action, active.progress, "في الانتظار…")
            return
        }
        val finished = job
        val wasList = finished?.action == "list" || finished?.action == "mirror"
        job = null
        val done = infos.lastOrNull()
        if (wasList && done?.state == WorkInfo.State.FAILED) listError = done.outputData.getString("error")
        reload()
        // نهاية أرشفة أو تجربة بدأت والتطبيق مفتوح: صوت + شاشة الملخص
        if (finished != null && (finished.action == "run" || finished.action == "dry")) {
            val run = prefs.lastRun?.let { JSONObject(it) }
            if (run != null && run.optLong("time") >= jobStartedAt) {
                celebration = run
                Sounds.play(getApplication(), if (run.optBoolean("ok")) Sounds.Snd.DONE else Sounds.Snd.ERROR)
            } else if (done?.outputData?.getBoolean("ok", true) == false) {
                Sounds.play(getApplication(), Sounds.Snd.ERROR)
            }
        }
    }

    /** ملخص آخر تشغيل لعرضه في شاشة الاحتفال (null: لا شيء) */
    var celebration by mutableStateOf<JSONObject?>(null)

    var soundsEnabled by mutableStateOf(prefs.soundsEnabled); private set
    var hapticsEnabled by mutableStateOf(prefs.hapticsEnabled); private set
    fun updateSounds(on: Boolean) { prefs.soundsEnabled = on; soundsEnabled = on; if (on) Sounds.play(getApplication(), Sounds.Snd.DONE) }
    fun updateHaptics(on: Boolean) { prefs.hapticsEnabled = on; hapticsEnabled = on }

    /** نقرة خفيفة عند اختيار محادثة */
    fun tick() = Sounds.play(getApplication(), Sounds.Snd.TICK, 0.35f)

    fun reload() {
        known = prefs.knownChats
        knownAt = prefs.knownChatsAt
        lastRun = prefs.lastRun?.let { JSONObject(it) }
        lastAuto = prefs.lastAuto
        history = prefs.history
        driveFolderId = prefs.driveFolderId
        batteryExempt = getApplication<Application>().getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
        viewModelScope.launch {
            val (age, prog, phone) = withContext(Dispatchers.IO) {
                Triple(engine.backupAgeHours(), engine.chatProgress(),
                    if (prefs.importContacts) runCatching { PhoneContacts.read(getApplication(), prefs.countryCode) }.getOrDefault(emptyMap()) else emptyMap())
            }
            backupAge = age
            progressByChat = prog
            phoneContacts = phone
            phoneContactsCount = phone.size
            readable = withContext(Dispatchers.IO) { engine.mirror.chats() }
        }
    }

    // مجلدات الأرشيف في الدرايف
    var archiveFolders by mutableStateOf<List<com.modwahi.waarchiver.io.DriveClient.ArchiveRoot>?>(null); private set
    var archiveFoldersError by mutableStateOf<String?>(null); private set

    fun loadArchiveFolders() {
        archiveFolders = null
        archiveFoldersError = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { engine.archiveRoots() } }
                .onSuccess { archiveFolders = it }
                .onFailure { archiveFoldersError = it.message ?: "تعذّر البحث في Google Drive"; archiveFolders = emptyList() }
        }
    }

    /** يواصل الأرشفة في مجلد آخر (من القائمة أو برابط). يرجع رسالة خطأ أو null. */
    suspend fun chooseArchiveFolder(idOrLink: String): String? = withContext(Dispatchers.IO) {
        runCatching { engine.useDriveFolder(idOrLink) }.fold({ name ->
            driveFolderId = prefs.driveFolderId
            reload()
            message = "سيواصل التطبيق الأرشفة في «$name».\nفي التشغيل القادم يكمل آخر ${prefs.firstRunDays} يوم ويدمجها مع الموجود بدون تكرار."
            null
        }, { it.message ?: "تعذّر استخدام هذا المجلد" })
    }

    /** يفحص صفحة Releases. manual: يُظهر رسالة حتى لو لا يوجد جديد. */
    fun checkUpdates(manual: Boolean) {
        if (updateState != null) return
        updateState = "checking"
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { com.modwahi.waarchiver.io.Updates.latest() } }
            updateState = null
            prefs.lastUpdateCheck = System.currentTimeMillis()
            r.onSuccess { rel ->
                update = rel?.takeIf { com.modwahi.waarchiver.io.Updates.newer(it.version, currentVersion) }
                if (manual && update == null) message = "لديك أحدث إصدار ($currentVersion) ✓"
            }.onFailure { if (manual) message = it.message ?: "تعذّر فحص التحديثات" }
        }
    }

    /** ينزّل التحديث ويتحقق من مفتاح التوقيع ثم يفتح التثبيت */
    fun installUpdate(a: Actions) {
        val rel = update ?: return
        val app = getApplication<Application>()
        if (!com.modwahi.waarchiver.io.Updates.canInstall(app)) {
            message = "اسمح لـ «أرشيف واتساب» بتثبيت التحديثات: فعّل «السماح من هذا المصدر» ثم ارجع واضغط «تحديث الآن» مرة أخرى."
            a.openInstallPermission()
            return
        }
        updateState = "downloading"
        updatePct = 0f
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val f = com.modwahi.waarchiver.io.Updates.download(app, rel) { p -> updatePct = p.toFloat() }
                    val mine = com.modwahi.waarchiver.io.Updates.installedCert(app)
                    val theirs = com.modwahi.waarchiver.io.Updates.fileCert(app, f)
                    if (mine != null && theirs != null && mine != theirs) {
                        throw IllegalStateException(
                            "هذا الإصدار موقّع بمفتاح غير مفتاح النسخة المثبّتة، فلن يقبله أندرويد كتحديث.\n" +
                                "المثبّت: $mine\nالجديد: $theirs\n" +
                                "الحل: احذف التطبيق ثم ثبّت الإصدار الجديد من صفحة Releases (ستحتاج لإعادة الإعداد).")
                    }
                    f
                }
            }
            res.onSuccess { f ->
                updateState = "installing"
                withContext(Dispatchers.IO) { runCatching { com.modwahi.waarchiver.io.Updates.install(app, f) } }
                    .onFailure { message = "تعذّر بدء التثبيت: ${it.message}" }
                updateState = null
            }.onFailure {
                updateState = null
                message = it.message ?: "تعذّر تنزيل التحديث"
            }
        }
    }

    /** للقطات الشاشة في الاختبارات فقط */
    @androidx.annotation.VisibleForTesting
    fun previewJob(j: Job?) { job = j }

    @androidx.annotation.VisibleForTesting
    fun previewUpdate(r: com.modwahi.waarchiver.io.Updates.Release?) { update = r }

    fun dismissCrash() {
        App.crashFile(getApplication()).delete()
        crash = null
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
        reload()
        if (b == null) "اخترت المجلد، لكن لم أجد ملف نسخة (msgstore*.db.crypt15) في مجلد Databases. فعّل النسخ الاحتياطي المشفّر في واتساب ثم اضغط «نسخ احتياطي»."
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

    fun selectAll(jids: List<String>, on: Boolean) {
        val m = choices.toMutableMap()
        for (j in jids) m[j] = (m[j] ?: ChatChoice(j, false)).copy(selected = on)
        saveChoices(m)
    }

    fun setFolder(jid: String, folder: String) {
        val c = choices[jid] ?: ChatChoice(jid, true)
        saveChoices(choices + (jid to c.copy(folder = folder.trim())))
    }

    fun setOwnerName(c: KnownChat, name: String) = setName(phoneOf(c), name)

    /** تنزيل ما أُرشف سابقاً من الوجهة للقراءة داخل التطبيق */
    fun syncReadable() {
        listError = null
        ArchiveWorker.enqueue(getApplication(), "mirror")
    }

    fun dayMessages(folder: String, date: String) = engine.mirror.day(folder, date)

    private val images = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(40)

    /** صورة مصغّرة من مجلد واتساب (قراءة فقط) لعرضها في القارئ. null إن لم تعد موجودة. */
    suspend fun thumbnail(rel: String): androidx.compose.ui.graphics.ImageBitmap? = images.get(rel) ?: withContext(Dispatchers.IO) {
        runCatching {
            val f = engine.source().media(rel) ?: return@runCatching null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            f.open().use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 720) sample *= 2
            val bmp = f.open().use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
            bmp?.asImageBitmap()
        }.getOrNull()?.also { images.put(rel, it) }
    }

    fun setName(number: String, name: String) {
        val num = Naming.normalizeNumber(number)
        if (num.isEmpty()) return
        val m = contacts.toMutableMap()
        if (name.isBlank()) m.remove(num) else m[num] = name.trim()
        contacts = m
        prefs.contacts = m
    }

    /** الرقم الحقيقي للمحادثة الخاصة (حتى لو كان معرّفها @lid) */
    fun phoneOf(c: KnownChat): String = c.phone.ifEmpty { Archiver.numberOf(c.jid) }

    /** الاسم لرقم: ما كتبته في التطبيق، ثم جهات الاتصال */
    fun nameFor(number: String): String? =
        contacts[number]?.takeIf { it.isNotBlank() } ?: phoneContacts[number]?.takeIf { it.isNotBlank() }

    fun displayName(c: KnownChat): String {
        if (c.subject.isNotEmpty()) return c.subject
        val num = phoneOf(c)
        nameFor(num)?.let { return it }
        if (c.pushName.isNotEmpty()) return "~" + c.pushName
        return if (num.isNotEmpty()) ltr("+$num") else c.jid.substringBefore('@')
    }

    /** اسم المجلد الذي سيُستخدم إن تُرك الحقل فارغاً (الاسم المستخدم سابقاً إن وُجد). */
    fun defaultFolder(c: KnownChat): String {
        progressByChat[c.jid]?.folder?.takeIf { it.isNotEmpty() }?.let { return it }
        val num = phoneOf(c)
        val name = c.subject.ifEmpty {
            nameFor(num) ?: if (c.jid.endsWith("@lid")) c.pushName.ifEmpty { num.ifEmpty { c.jid } } else num.ifEmpty { c.jid }
        }
        return Naming.sanitizeName(name)
    }

    fun folderFor(c: KnownChat): String = choices[c.jid]?.folder?.takeIf { it.isNotEmpty() } ?: defaultFolder(c)

    val selectedCount get() = choices.values.count { it.selected }

    // ---------- التشغيل ----------
    fun runNow() = ArchiveWorker.enqueue(getApplication(), "run")
    fun dryRun() = ArchiveWorker.enqueue(getApplication(), "dry")

    /** إيقاف التشغيل الجاري (اليدوي أو المجدول). الجدولة الدورية تبقى مفعّلة. */
    fun stop() {
        wm.cancelUniqueWork(ArchiveWorker.MANUAL)
        if (job?.action == "scheduled") {
            wm.cancelUniqueWork(ArchiveWorker.PERIODIC)
            ArchiveWorker.schedule(getApplication())
        }
    }

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
        days?.takeIf { it in 1..3650 }?.let { prefs.firstRunDays = it; firstRunDays = it }
        maxMb?.takeIf { it in 1..4096 }?.let { prefs.maxMediaMb = it; maxMediaMb = it }
    }

    fun updateTimezone(tz: String): Boolean {
        val ok = runCatching { java.time.ZoneId.of(tz.trim()) }.isSuccess
        if (ok) { prefs.timezone = tz.trim(); timezone = tz.trim() }
        return ok
    }

    fun updateMeName(n: String) {
        if (n.isNotBlank()) { prefs.meName = n.trim(); meName = n.trim() }
    }

    fun updateTheme(m: Int) { prefs.themeMode = m; themeMode = m }
    fun updateNotifySuccess(on: Boolean) { prefs.notifySuccess = on; notifySuccess = on }

    fun updateImportContacts(on: Boolean) {
        prefs.importContacts = on; importContacts = on
        reload()
    }

    fun updateCountryCode(cc: String) {
        val d = cc.filter { it.isDigit() }.take(4)
        prefs.countryCode = d; countryCode = d
        reload()
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
