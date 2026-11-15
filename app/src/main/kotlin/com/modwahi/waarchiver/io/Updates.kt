package com.modwahi.waarchiver.io

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.modwahi.waarchiver.work.Notifier
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * «تحديث متوفر»: يقرأ آخر إصدار من صفحة Releases في GitHub (المستودع عام، لا يحتاج إذناً)،
 * ينزّل الـ APK، يتحقق أنه موقّع بنفس مفتاح التطبيق المثبّت، ثم يفتح شاشة التثبيت.
 */
object Updates {
    const val REPO = "Modwahi/whatsapp-archiver-app"

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkName: String, val apkSize: Long, val page: String)

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(2, TimeUnit.MINUTES).build()

    /** هل a أحدث من b؟ (أرقام 1.2.3) */
    fun newer(a: String, b: String): Boolean {
        val pa = a.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parseRelease(o: JSONObject): Release? {
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk")) {
                return Release(o.optString("tag_name").removePrefix("v"), o.optString("body"),
                    a.getString("browser_download_url"), name, a.optLong("size"), o.optString("html_url"))
            }
        }
        return null
    }

    private fun get(url: String): okhttp3.Response = http.newCall(Request.Builder().url(url)
        .header("Accept", "application/vnd.github+json")
        .header("Cache-Control", "no-cache")
        .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
        .build()).execute()

    /**
     * أحدث إصدار فيه ملف APK، أو null.
     * لا نعتمد على /releases/latest وحده لأن خوادم GitHub قد تُرجع نسخة قديمة منه لدقائق في بعض المناطق:
     * نقرأ قائمة الإصدارات (بدون ذاكرة مؤقتة) ونأخذ أعلى رقم، ونتحقق أيضاً من صفحة الإصدار الأخير في الموقع.
     */
    fun latest(): Release? {
        val found = ArrayList<Release>()
        var lastError: Exception? = null
        try {
            get("https://api.github.com/repos/$REPO/releases?per_page=10&t=${System.currentTimeMillis()}").use { r ->
                if (!r.isSuccessful) throw IllegalStateException("تعذّر فحص التحديثات (${r.code})")
                val a = org.json.JSONArray(r.body.string())
                for (i in 0 until a.length()) parseRelease(a.getJSONObject(i))?.let(found::add)
            }
        } catch (e: Exception) { lastError = e }
        // طريق ثانٍ: صفحة «آخر إصدار» في الموقع تحوّل إلى /releases/tag/vX.Y.Z
        try {
            val noRedirect = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            noRedirect.newCall(Request.Builder().url("https://github.com/$REPO/releases/latest")
                .header("Cache-Control", "no-cache").build()).execute().use { r ->
                val tag = r.header("Location")?.substringAfter("/releases/tag/", "")?.takeIf { it.isNotEmpty() }
                if (tag != null && found.none { it.version == tag.removePrefix("v") }) {
                    get("https://api.github.com/repos/$REPO/releases/tags/$tag").use { t ->
                        if (t.isSuccessful) parseRelease(JSONObject(t.body.string()))?.let(found::add)
                    }
                }
            }
        } catch (e: Exception) { if (found.isEmpty()) lastError = lastError ?: e }
        if (found.isEmpty() && lastError != null) throw lastError
        return found.maxWithOrNull { a, b -> if (newer(a.version, b.version)) 1 else if (newer(b.version, a.version)) -1 else 0 }
    }

    /** ينزّل الـ APK إلى cache التطبيق مع التقدّم (0..1) */
    fun download(context: Context, r: Release, onProgress: (Double) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, r.apkName)
        http.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("تعذّر تنزيل التحديث (${resp.code})")
            val total = resp.body.contentLength().takeIf { it > 0 } ?: r.apkSize
            resp.body.byteStream().use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toDouble() / total)
                    }
                }
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun certs(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString(":") { "%02X".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private val certFlag get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    fun installedCert(context: Context): String? =
        certs(context.packageManager.getPackageInfo(context.packageName, certFlag)).firstOrNull()

    fun fileCert(context: Context, apk: File): String? =
        certs(context.packageManager.getPackageArchiveInfo(apk.path, certFlag)).firstOrNull()

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** يبدأ التثبيت عبر PackageInstaller؛ أندرويد يعرض شاشة التأكيد للمستخدم */
    fun install(context: Context, apk: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val cb = PendingIntent.getBroadcast(context, 7, Intent(context, InstallReceiver::class.java), flags)
            s.commit(cb.intentSender)
        }
    }
}

/** نتيجة التثبيت: طلب تأكيد المستخدم، أو رسالة فشل واضحة */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Notifier.failure(context, "لم يكتمل تثبيت التحديث. $msg")
            }
        }
    }
}
