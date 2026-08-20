package me.kavishdevar.librepods.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateRecoveryPolicyTest {
    @Test
    fun missingHostLibSkipsDuplicateSameSessionAttempt() {
        assertFalse(shouldRetryHeartRateInCurrentAacpSession(hostLibHidAdvertised = false))
    }

    @Test
    fun advertisedHostLibAllowsAssistedSameSessionAttempt() {
        assertTrue(shouldRetryHeartRateInCurrentAacpSession(hostLibHidAdvertised = true))
    }
}
