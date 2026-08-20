package me.kavishdevar.librepods.features

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementAndPresentationPoliciesTest {
    @Test
    fun notificationDedupeRejectsRapidUpdatesForSameKey() {
        val dedupe = RecentNotificationKeyDedupe(ttlMillis = 1_000L)
        assertTrue(dedupe.shouldAccept("pkg|1", 100L))
        assertFalse(dedupe.shouldAccept("pkg|1", 500L))
        assertTrue(dedupe.shouldAccept("pkg|1", 1_101L))
    }

    @Test
    fun notificationDedupeAllowsAKeyAfterRemoval() {
        val dedupe = RecentNotificationKeyDedupe(ttlMillis = 60_000L)
        assertTrue(dedupe.shouldAccept("pkg|1", 100L))
        dedupe.remove("pkg|1")
        assertTrue(dedupe.shouldAccept("pkg|1", 200L))
    }

    @Test
    fun volumeRestoreRequiresUntouchedFeatureVolume() {
        assertTrue(VolumeRestorationPolicy.shouldRestore(4, 4))
        assertFalse(VolumeRestorationPolicy.shouldRestore(5, 4))
    }

    @Test
    fun pauseRestoreRequiresFeaturePauseAndNoLaterUserPlaybackChange() {
        val tracker = PauseRestorationTracker()
        tracker.onPauseIssued()
        tracker.onPlaybackState(isPaused = true)
        assertTrue(tracker.shouldResume(isCurrentlyPaused = true))

        tracker.onPlaybackState(isPaused = false)
        assertFalse(tracker.shouldResume(isCurrentlyPaused = true))
    }

    @Test
    fun pauseRestoreIsConservativeUntilPauseWasObserved() {
        val tracker = PauseRestorationTracker()
        tracker.onPauseIssued()
        assertFalse(tracker.shouldResume(isCurrentlyPaused = true))
    }

}
