package com.modwahi.waarchiver.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPolicyTest {
    private val h = 3_600_000L

    @Test fun backoffAndStop() {
        val m = 1000L
        assertTrue(AutoPolicy.mayRun(m, null, 0))
        var f = AutoPolicy.afterFailure(m, null, 0)
        assertTrue(AutoPolicy.shouldNotify(f))
        assertFalse("ننتظر ساعتين", AutoPolicy.mayRun(m, f, 1 * h))
        assertTrue(AutoPolicy.mayRun(m, f, 2 * h))
        f = AutoPolicy.afterFailure(m, f, 2 * h)
        assertFalse(AutoPolicy.shouldNotify(f))
        assertFalse(AutoPolicy.mayRun(m, f, 5 * h))
        assertTrue(AutoPolicy.mayRun(m, f, 6 * h))
        f = AutoPolicy.afterFailure(m, f, 6 * h)
        assertTrue(AutoPolicy.mayRun(m, f, 14 * h))
        f = AutoPolicy.afterFailure(m, f, 14 * h)
        assertEquals(AutoPolicy.MAX_FAILS, f.count)
        assertTrue("إشعار أخير عند التوقف", AutoPolicy.shouldNotify(f))
        assertFalse("توقف حتى نسخة جديدة", AutoPolicy.mayRun(m, f, 100 * h))
        assertTrue("نسخة جديدة: نحاول فوراً", AutoPolicy.mayRun(m + 1, f, 100 * h))
        assertEquals(1, AutoPolicy.afterFailure(m + 1, f, 100 * h).count)
    }
}

class UpdatesTest {
    @Test fun versionCompare() {
        val n = com.modwahi.waarchiver.io.Updates::newer
        assertTrue(n("1.3.1", "1.3.0"))
        assertTrue(n("v1.10.0", "1.9.9"))
        assertTrue(n("2.0", "1.99.99"))
        assertFalse(n("1.3.0", "1.3.0"))
        assertFalse(n("1.2.9", "1.3.0"))
    }
}
