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
        val p = pct?.let { (it * 100).toInt() } ?: -1
        if (pct != null && p == lastPct) return
        lastPct = p
        setProgress(workDataOf("stage" to stage, "pct" to p))
        runCatching { setForeground(foreground(stage, pct)) }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val engine = Engine(applicationContext)
        val prefs = engine.prefs
        if (!prefs.setupDone && action != "list") return@withContext Result.success()
        try {
            // إشعار foreground أثناء التشغيل. قد يرفضه أندرويد 12+ إن كان التطبيق في الخلفية ولم يُستثنَ من
            // تحسين البطارية — نكمل بدونه.
            try { setForeground(foreground("بدء التشغيل…", null)) } catch (e: Exception) {
                engine.log.warning("تعذّر إظهار إشعار التشغيل: ${e.javaClass.simpleName}")
            }
            val report: (String, Double?) -> Unit = { s, p -> kotlinx.coroutines.runBlocking { progress(s, p) } }
            when (action) {
                "list" -> {
                    engine.listChats(report)
                    Result.success(workDataOf("ok" to true))
                }
                else -> {
                    val (summary, exported) = engine.run(scheduled = action == "scheduled", dryRun = action == "dry", progress = report)
                    if (summary == null) {
                        checkStale(engine)
                        return@withContext Result.success()
                    }
                    if (!summary.ok) Notifier.failure(applicationContext, summary.errors.firstOrNull()
                        ?: summary.chats.firstOrNull { it.error != null }?.let { "${it.name}: ${it.error}" } ?: "")
                    else if (action == "dry") Notifier.info(applicationContext, "اكتملت التجربة", "الناتج في $exported")
                    if (summary.warnings.isNotEmpty()) notifyStaleOnce(engine, summary.warnings.first())
                    Result.success(workDataOf("ok" to summary.ok, "exported" to exported))
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            engine.log.error(msg)
            if (action == "list") return@withContext Result.failure(workDataOf("error" to msg))
            Notifier.failure(applicationContext, msg)
            Result.success(workDataOf("ok" to false, "error" to msg))
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
