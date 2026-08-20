package me.kavishdevar.librepods.features.findmy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindMyNetworkRecoveryPolicyTest {
    @Test
    fun `empty key result changes record without spending a passcode attempt`() {
        val decision = decideRecoveryFailure(FindMyNetworkReasons.NO_KEYCHAIN_KEYS, 1)

        assertEquals(1, decision.nextAttempts)
        assertFalse(decision.stopSession)
        assertTrue(decision.clearDeviceSelection)
        assertFalse(decision.stoppedAfterLimit)
    }

    @Test
    fun `network failure is not presented as a rejected passcode`() {
        val decision = decideRecoveryFailure("unknown", 1)

        assertEquals(1, decision.nextAttempts)
        assertFalse(decision.stopSession)
        assertFalse(decision.clearDeviceSelection)
    }

    @Test
    fun `only explicit passcode rejection advances and caps attempts`() {
        val first = decideRecoveryFailure(FindMyNetworkReasons.PASSCODE_REJECTED, 0)
        val final = decideRecoveryFailure(FindMyNetworkReasons.PASSCODE_REJECTED, 2)

        assertEquals(1, first.nextAttempts)
        assertFalse(first.stopSession)
        assertEquals(FIND_MY_MAX_PASSCODE_ATTEMPTS, final.nextAttempts)
        assertTrue(final.stopSession)
        assertTrue(final.clearDeviceSelection)
        assertTrue(final.stoppedAfterLimit)
    }
}
