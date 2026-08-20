package me.kavishdevar.librepods.features

import java.util.LinkedHashMap

enum class AnnouncementHandlingMode(val preferenceValue: String) {
    PAUSE("pause"),
    DUCK("duck");

    companion object {
        fun fromPreference(value: String?): AnnouncementHandlingMode =
            entries.firstOrNull { it.preferenceValue == value } ?: PAUSE
    }
}

class RecentNotificationKeyDedupe(
    private val ttlMillis: Long = 120_000L,
    private val maxEntries: Int = 512
) {
    private val seen = LinkedHashMap<String, Long>(maxEntries, 0.75f, true)

    @Synchronized
    fun shouldAccept(key: String, nowMillis: Long): Boolean {
        prune(nowMillis)
        val previous = seen[key]
        if (previous != null && nowMillis - previous < ttlMillis) return false
        seen[key] = nowMillis
        while (seen.size > maxEntries) {
            val eldest = seen.entries.iterator().next().key
            seen.remove(eldest)
        }
        return true
    }

    @Synchronized
    fun remove(key: String) {
        seen.remove(key)
    }

    @Synchronized
    fun clear() = seen.clear()

    private fun prune(nowMillis: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMillis - entry.value >= ttlMillis) iterator.remove()
        }
    }
}

object VolumeRestorationPolicy {
    fun shouldRestore(currentVolume: Int, featureSetVolume: Int): Boolean =
        currentVolume == featureSetVolume
}

class PauseRestorationTracker {
    private var pauseIssued = false
    private var pauseObserved = false
    private var userIntervened = false

    fun onPauseIssued() {
        pauseIssued = true
    }

    fun onPlaybackState(isPaused: Boolean) {
        if (!pauseIssued) return
        if (isPaused) {
            pauseObserved = true
        } else if (pauseObserved) {
            userIntervened = true
        }
    }

    fun onSessionDestroyed() {
        userIntervened = true
    }

    fun shouldResume(isCurrentlyPaused: Boolean): Boolean =
        pauseIssued && pauseObserved && !userIntervened && isCurrentlyPaused
}
