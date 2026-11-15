package com.modwahi.waarchiver.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.modwahi.waarchiver.data.Mode
import com.modwahi.waarchiver.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * العامل الوحيد في التطبيق. الأفعال:
 *  scheduled — الدوري (كل ساعة تقريباً): لا يعمل فعلياً إلا مرة بعد كل نسخة احتياطية جديدة
 *  run       — «شغّل الآن»
 *  dry       — «تجربة بدون رفع» (يحفظ في Download)
 *  list      — قراءة قائمة المحادثات من النسخة
 */
class ArchiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val ACTION = "action"
        const val PERIODIC = "archive_periodic"
        const val MANUAL = "archive_manual"

        fun enqueue(context: Context, action: String) {
            val req = OneTimeWorkRequestBuilder<ArchiveWorker>()
                .setInputData(workDataOf(ACTION to action))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(action)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, req)
        }

        /** الجدولة الدورية حسب الإعدادات (الشحن والواي فاي قابلان للتعطيل). */
        fun schedule(context: Context) {
            val prefs = Prefs(context)
            val wm = WorkManager.getInstance(context)
            if (!prefs.setupDone || !prefs.scheduleEnabled) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val network = when {
                prefs.mode == Mode.LOCAL -> NetworkType.NOT_REQUIRED
                prefs.requireWifi -> NetworkType.UNMETERED
                else -> NetworkType.CONNECTED
            }
            val constraints = Constraints.Builder()
                .setRequiresCharging(prefs.requireCharging)
                .setRequiredNetworkType(network)
                .setRequiresStorageNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<ArchiveWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(workDataOf(ACTION to "scheduled"))
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    private val action get() = inputData.getString(ACTION) ?: "scheduled"

    private fun foreground(text: String, pct: Double?): ForegroundInfo {
        val n = Notifier.progress(applicationContext, text, pct)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(Notifier.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Notifier.ID_PROGRESS, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("بدء التشغيل…", null)

    private var lastPct = -1

    private suspend fun progress(stage: String, pct: Double?) {
        if (isStopped) throw StoppedByUser()
        val p = pct?.let { (it * 100).toInt() } ?: -1
        if (pct != null && p == lastPct) return
        lastPct = p
        setProgress(workDataOf("stage" to stage, "pct" to p))
        try { setForeground(foreground(stage, pct)) } catch (_: Throwable) {}
    }

    private fun recordAuto(prefs: Prefs, tone: String, text: String) {
        prefs.lastAuto = org.json.JSONObject().put("time", System.currentTimeMillis()).put("tone", tone).put("text", text)
    }

    /** سبب إيقاف أندرويد للعمل (للتشخيص) */
    private fun stopReasonText(): String {
        val r = if (Build.VERSION.SDK_INT >= 31) runCatching { stopReason }.getOrDefault(-1) else -1
        return when (r) {
            WorkInfo.STOP_REASON_TIMEOUT -> "انتهت المهلة التي يسمح بها أندرويد للعمل في الخلفية"
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "فُصل الشاحن"
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "انقطع الواي فاي"
            WorkInfo.STOP_REASON_APP_STANDBY, WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION ->
                "أندرويد يقيّد التطبيق في الخلفية (البطارية)"
            WorkInfo.STOP_REASON_QUOTA -> "استُهلكت حصة العمل في الخلفية"
            WorkInfo.STOP_REASON_DEVICE_STATE -> "حالة الجهاز (توفير الطاقة)"
            WorkInfo.STOP_REASON_CANCELLED_BY_APP, WorkInfo.STOP_REASON_USER -> "أوقفته يدوياً"
            else -> "أوقفه النظام" + if (r > 0) " (رمز $r)" else ""
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val engine = Engine(applicationContext)
        val prefs = engine.prefs
        if (!prefs.setupDone && action != "list" && action != "mirror") return@withContext Result.success()
        val scheduled = action == "scheduled"
        var backupMs: Long? = null
        try {
            if (scheduled) {
                // ١) فحص سريع بدون فك: معظم الفحوص الساعية تنتهي هنا
                engine.scheduledSkipReason()?.let { recordAuto(prefs, "info", it); checkStale(engine); return@withContext Result.success() }
                backupMs = engine.backupMtimeMs()
                // ٢) انتظار متزايد بعد الفشل لنفس النسخة
                if (!AutoPolicy.mayRun(backupMs, prefs.failState, System.currentTimeMillis())) {
                    val f = prefs.failState!!
                    recordAuto(prefs, "warn", if (f.count >= AutoPolicy.MAX_FAILS)
                        "توقفت المحاولات التلقائية لهذه النسخة بعد ${f.count} محاولات فاشلة — شغّله يدوياً أو انتظر النسخة القادمة"
                    else "بانتظار إعادة المحاولة بعد فشل سابق")
                    return@withContext Result.success()
                }
            }
            // ٣) إشعار التشغيل (foreground): بدونه يوقف أندرويد العمل بعد ~10 دقائق
            val fgOk = try { setForeground(foreground("بدء التشغيل…", null)); true } catch (e: Throwable) {
                engine.log.warning("تعذّر بدء التشغيل في الخلفية: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (scheduled && !fgOk) {
                // لا نبدأ عملاً ثقيلاً سيُقطع في منتصفه
                val msg = "أندرويد منع التطبيق من العمل في الخلفية. افتح التطبيق ← الرئيسية ← «اسمح بالعمل في الخلفية»، " +
                    "وفي سامسونج: الإعدادات ← التطبيقات ← أرشيف واتساب ← البطارية ← «غير مقيّد»."
                recordAuto(prefs, "error", msg)
                val today = LocalDate.now().toString()
                if (prefs.bgDeniedNotifiedDay != today) {
                    prefs.bgDeniedNotifiedDay = today
                    Notifier.failure(applicationContext, msg)
                }
                return@withContext Result.success()
            }
            val report: (String, Double?) -> Unit = { s, p -> kotlinx.coroutines.runBlocking { progress(s, p) } }
            when (action) {
                "list" -> {
                    engine.listChats(report)
                    Result.success(workDataOf("ok" to true))
                }
                "mirror" -> {
                    val n = engine.syncMirror(report)
                    Result.success(workDataOf("ok" to true, "fetched" to n))
                }
                else -> {
                    val (summary, exported) = engine.run(scheduled = scheduled, dryRun = action == "dry", progress = report)
                    if (isStopped) {
                        if (scheduled) { recordAuto(prefs, "warn", "توقف قبل أن يكتمل: ${stopReasonText()}"); noteFailure(prefs, backupMs, null) }
                        return@withContext Result.success()
                    }
                    if (summary == null) {
                        if (scheduled) recordAuto(prefs, "info", "لا توجد نسخة احتياطية جديدة منذ آخر أرشفة")
                        checkStale(engine)
                        return@withContext Result.success()
                    }
                    val firstError = summary.errors.firstOrNull()
                        ?: summary.chats.firstOrNull { it.error != null }?.let { "${it.name}: ${it.error}" } ?: ""
                    if (summary.ok) {
                        if (!summary.dryRun) prefs.failState = null
                        if (scheduled) recordAuto(prefs, "ok", "تمت الأرشفة: رسائل ${summary.chats.sumOf { it.messages }}، " +
                            "وسائط ${summary.chats.sumOf { it.media }}")
                    }
                    when {
                        !summary.ok && scheduled -> {
                            recordAuto(prefs, "error", "فشل: $firstError")
                            noteFailure(prefs, backupMs, firstError)
                        }
                        !summary.ok -> Notifier.failure(applicationContext, firstError)
                        action == "dry" -> Notifier.info(applicationContext, "اكتملت التجربة", "الناتج في $exported")
                        prefs.notifySuccess -> Notifier.info(applicationContext, "تمت الأرشفة ✓",
                            "رسائل ${summary.chats.sumOf { it.messages }} و وسائط ${summary.chats.sumOf { it.media }} في ${summary.chats.size} محادثة")
                    }
                    if (summary.warnings.isNotEmpty()) notifyStaleOnce(engine, summary.warnings.first())
                    Result.success(workDataOf("ok" to summary.ok, "exported" to exported))
                }
            }
        } catch (e: com.modwahi.waarchiver.core.BusyError) {
            // تشغيل آخر يعمل (يدوي أو سابق لم ينتهِ): ليس فشلاً، نتخطى بهدوء
            engine.log.info("تخطيت: يوجد تشغيل آخر قيد العمل")
            if (scheduled) recordAuto(prefs, "info", "تخطيت الفحص لأن تشغيلاً آخر كان يعمل")
            Result.success()
        } catch (e: StoppedByUser) {
            engine.log.info("توقف التشغيل: ${stopReasonText()}")
            if (scheduled) { recordAuto(prefs, "warn", "توقف قبل أن يكتمل: ${stopReasonText()}"); noteFailure(prefs, backupMs, null) }
            Result.success(workDataOf("ok" to false, "error" to e.message))
        } catch (e: Throwable) {
            // Throwable وليس Exception فقط: حتى أخطاء مثل OutOfMemoryError تظهر رسالة بدل انهيار التطبيق
            val msg = (e.message ?: "").let { if (e is Exception && it.isNotEmpty()) it else "${e.javaClass.simpleName}: $it" }
            engine.log.error(msg + "\n" + com.modwahi.waarchiver.App.describe(e))
            if (action == "list" || action == "mirror") return@withContext Result.failure(workDataOf("error" to msg))
            if (scheduled) { recordAuto(prefs, "error", "فشل: $msg"); noteFailure(prefs, backupMs, msg) }
            else Notifier.failure(applicationContext, msg)
            Result.success(workDataOf("ok" to false, "error" to msg))
        }
    }

    /** فشل تشغيل مجدول: انتظار متزايد، وإشعار مرة واحدة لكل نسخة */
    private fun noteFailure(prefs: Prefs, backupMs: Long?, error: String?) {
        val f = AutoPolicy.afterFailure(backupMs, prefs.failState, System.currentTimeMillis())
        prefs.failState = f
        if (error != null && AutoPolicy.shouldNotify(f)) {
            Notifier.failure(applicationContext, error + if (f.count >= AutoPolicy.MAX_FAILS)
                "\nتوقفت المحاولات التلقائية لهذه النسخة. شغّله يدوياً بعد إصلاح المشكلة." else
                "\nسأعيد المحاولة تلقائياً بعد ${AutoPolicy.waitMs(f.count) / 3_600_000} ساعة.")
        }
    }

    /** في الوضع المجدول بلا نسخة جديدة: نذكّر مرة يومياً إن كانت النسخة أقدم من الحد. */
    private fun checkStale(engine: Engine) {
        val age = engine.backupAgeHours() ?: return
        if (age > engine.prefs.staleBackupHours) {
            notifyStaleOnce(engine, "النسخة الاحتياطية لم تتحدث منذ ${age.toInt()} ساعة " +
                "(تأكد أن النسخ الاحتياطي اليومي مفعّل في واتساب)")
        }
    }

    private fun notifyStaleOnce(engine: Engine, text: String) {
        val today = LocalDate.now().toString()
        if (engine.prefs.staleNotifiedDay == today) return
        engine.prefs.staleNotifiedDay = today
        Notifier.warning(applicationContext, text)
    }
}
