package com.modwahi.waarchiver.work

/**
 * قواعد التشغيل التلقائي (منطق صافٍ قابل للاختبار):
 * - بعد فشل تشغيل مجدول لنفس النسخة الاحتياطية ننتظر قبل إعادة المحاولة: ساعتان، ثم 4، ثم 8.
 * - بعد 4 محاولات فاشلة لنفس النسخة نتوقف حتى تأتي نسخة جديدة (أو تشغيل يدوي).
 * - إشعار الفشل مرة واحدة لكل نسخة (أول فشل)، ثم مرة أخيرة عند التوقف.
 */
object AutoPolicy {
    const val MAX_FAILS = 4
    private const val BASE_WAIT_MS = 2 * 3_600_000L

    data class FailState(val backupMtimeMs: Long, val count: Int, val lastAtMs: Long)

    fun waitMs(count: Int): Long = BASE_WAIT_MS shl (count - 1).coerceIn(0, 2)

    /** هل نحاول الآن مع هذه النسخة؟ */
    fun mayRun(backupMtimeMs: Long?, fail: FailState?, nowMs: Long): Boolean {
        if (backupMtimeMs == null || fail == null || fail.backupMtimeMs != backupMtimeMs) return true
        if (fail.count >= MAX_FAILS) return false
        return nowMs - fail.lastAtMs >= waitMs(fail.count)
    }

    /** حالة الفشل بعد فشل جديد */
    fun afterFailure(backupMtimeMs: Long?, fail: FailState?, nowMs: Long): FailState {
        val m = backupMtimeMs ?: 0L
        val prev = if (fail != null && fail.backupMtimeMs == m) fail.count else 0
        return FailState(m, prev + 1, nowMs)
    }

    /** نُشعر عند أول فشل لهذه النسخة، وعند آخر محاولة قبل التوقف */
    fun shouldNotify(newFail: FailState): Boolean = newFail.count == 1 || newFail.count == MAX_FAILS
}
