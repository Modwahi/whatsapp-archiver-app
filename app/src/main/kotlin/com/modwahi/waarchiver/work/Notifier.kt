package com.modwahi.waarchiver.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.modwahi.waarchiver.R
import com.modwahi.waarchiver.ui.MainActivity

object Notifier {
    const val CH_PROGRESS = "progress"
    const val CH_ALERTS = "alerts_v2"
    const val CH_DONE = "done_v1"
    const val ID_PROGRESS = 1
    private const val ID_FAILURE = 2
    private const val ID_WARNING = 3
    private const val ID_INFO = 4
    private const val ID_DONE = 5

    /** ميغابايت بخانة عشرية واحدة */
    fun mb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0)

    private fun soundUri(context: Context, res: Int) =
        android.net.Uri.parse("android.resource://${context.packageName}/$res")

    private val notifAudio = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("alerts") // القناة القديمة بلا صوت خاص
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "أثناء الأرشفة", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "الأخطاء والتنبيهات", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(soundUri(context, R.raw.snd_error), notifAudio)
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "اكتمال الأرشفة", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(soundUri(context, R.raw.snd_done), notifAudio)
            description = "إشعار بملخص كل أرشفة ناجحة"
        })
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, text: String, pct: Double?): Notification =
        NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("أرشفة محادثات واتساب")
            .setContentText(text + (pct?.let { " ${(it * 100).toInt()}%" } ?: ""))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((pct ?: 0.0) * 100).toInt(), pct == null)
            .setContentIntent(openApp(context))
            .build()

    private fun post(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    /** إشعار اكتمال غني: العنوان، سطر الملخص، وتفاصيل كل محادثة عند التوسيع */
    fun done(context: Context, title: String, summary: String, details: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF0B7A66.toInt())
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (details.isEmpty()) summary else "$summary\n\n$details"))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID_DONE, n)
    }

    fun failure(context: Context, text: String) = post(context, ID_FAILURE, "فشلت أرشفة واتساب", text)
    fun warning(context: Context, text: String) = post(context, ID_WARNING, "تنبيه: النسخة الاحتياطية قديمة", text)
    fun info(context: Context, title: String, text: String) = post(context, ID_INFO, title, text)
}
