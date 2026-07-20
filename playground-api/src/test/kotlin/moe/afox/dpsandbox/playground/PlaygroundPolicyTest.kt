package moe.afox.dpsandbox.playground

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PlaygroundPolicyTest {
    @Test
    fun `rate limiter rejects requests inside the window and later recovers`() {
        val limiter = RequestRateLimiter(2)
        assertTrue(limiter.tryAcquire(0))
        assertTrue(limiter.tryAcquire(1))
        assertFalse(limiter.tryAcquire(2))
        assertTrue(limiter.tryAcquire(61.seconds.inWholeNanoseconds))
    }
}
