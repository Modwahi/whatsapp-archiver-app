package com.modwahi.waarchiver.io

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.modwahi.waarchiver.core.UploadError
import java.util.concurrent.TimeUnit

/**
 * تسجيل الدخول إلى Google بصلاحية drive.file فقط (غير حساسة: لا تحتاج مراجعة Google،
 * والموافقة لا تنتهي كل 7 أيام بعد نشر شاشة الموافقة Production).
 * الحساب يُختار مرة واحدة من شاشة الإعداد، ثم يُطلب التوكن بصمت في الخلفية.
 */
object GoogleAuth {
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    fun request(account: String?): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE)))
        .apply { if (!account.isNullOrBlank()) setAccount(Account(account, "com.google")) }
        .build()

    fun authorize(context: Context, account: String?): Task<AuthorizationResult> =
        Identity.getAuthorizationClient(context).authorize(request(account))

    private var lastToken: String? = null

    /** للخلفية فقط (يحجب الخيط). يرمي UploadError إن كان المستخدم يحتاج لتسجيل الدخول من جديد. */
    @Synchronized
    fun tokenBlocking(context: Context, account: String?, forceRefresh: Boolean): String {
        if (forceRefresh) lastToken?.let { runCatching { GoogleAuthUtil.clearToken(context, it) } }
        val res = try {
            Tasks.await(authorize(context, account), 60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw UploadError("تعذّر الحصول على إذن Google Drive: ${e.cause?.message ?: e.message}", e)
        }
        if (res.hasResolution()) {
            throw UploadError("يلزم تسجيل الدخول إلى Google من جديد: افتح التطبيق ← الإعدادات ← الحساب.")
        }
        return (res.accessToken ?: throw UploadError("Google لم يرجع توكن الوصول")).also { lastToken = it }
    }
}
