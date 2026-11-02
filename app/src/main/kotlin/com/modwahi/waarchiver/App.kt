package com.modwahi.waarchiver

import android.app.Application
import android.os.Build
import com.modwahi.waarchiver.core.ArchLog
import com.modwahi.waarchiver.work.ArchiveWorker
import com.modwahi.waarchiver.work.Notifier
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    companion object {
        fun crashFile(app: android.content.Context) = File(app.filesDir, "last_crash.txt")

        fun describe(t: Throwable): String {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            return ArchLog.redact(sw.toString())
        }
    }

    override fun onCreate() {
        super.onCreate()
        // أي انهيار يُحفظ نصه، ويظهر للمستخدم عند فتح التطبيق التالي ليرسله للمطوّر
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                crashFile(this).writeText(
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())} " +
                        "v${BuildConfig.VERSION_NAME} Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})\n" +
                        "thread: ${thread.name}\n" + describe(e)
                )
            }
            previous?.uncaughtException(thread, e)
        }
        Notifier.createChannels(this)
        ArchiveWorker.schedule(this)
    }
}
