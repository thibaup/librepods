package me.kavishdevar.librepods.services

import android.app.Notification
import android.bluetooth.BluetoothA2dp
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.features.AnnouncementHandlingMode
import me.kavishdevar.librepods.features.PauseRestorationTracker
import me.kavishdevar.librepods.features.RecentNotificationKeyDedupe
import me.kavishdevar.librepods.features.VolumeRestorationPolicy
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class NotificationAnnouncementService : NotificationListenerService() {
    private data class Announcement(
        val notificationKey: String,
        val packageName: String,
        val text: String
    )

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Announcement>()
    private val dedupe = RecentNotificationKeyDedupe()

    private lateinit var audioManager: AudioManager
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var preferences: SharedPreferences
    private var textToSpeech: TextToSpeech? = null
    private var ttsGeneration = 0
    private var ttsReady = false
    private var speaking = false
    private var currentUtteranceId: String? = null
    private var currentOriginPackage: String? = null
    private var currentMediaGuard: MediaGuard? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in ANNOUNCEMENT_PREFERENCE_KEYS) {
            handler.post {
                val retained = queue.filter { isStillEligible(it.packageName) }
                queue.clear()
                queue.addAll(retained)
                val currentPackage = currentOriginPackage
                if (currentPackage != null && !isStillEligible(currentPackage)) {
                    stopCurrentAnnouncement()
                }
                drainQueue()
            }
        }
    }
    private val connectionStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AirPodsNotifications.AIRPODS_DISCONNECTED &&
                intent?.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED
            ) return
            handler.postDelayed({
                if (ServiceManager.getService()?.isCurrentAirPodsConnected() != true) {
                    queue.clear()
                    stopCurrentAnnouncement()
                }
            }, 75L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        initializeTts()
        val filter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionStateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(connectionStateReceiver, filter)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (textToSpeech == null) initializeTts()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        handler.post {
            queue.clear()
            stopCurrentAnnouncement()
            shutdownTts()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (!preferences.getBoolean(PREF_ANNOUNCE_ENABLED, false)) return
        val selectedPackages = preferences.getStringSet(PREF_ANNOUNCE_PACKAGES, emptySet()).orEmpty()
        if (sbn.packageName !in selectedPackages) return
        if (ServiceManager.getService()?.isCurrentAirPodsConnected() != true) return
        val announcementText = buildAnnouncementText(sbn) ?: return
        if (!dedupe.shouldAccept(sbn.key, SystemClock.elapsedRealtime())) return
        handler.post {
            if (queue.size >= MAX_QUEUE_SIZE) queue.removeFirst()
            queue.addLast(Announcement(sbn.key, sbn.packageName, announcementText))
            drainQueue()
        }
    }


    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        dedupe.remove(sbn.key)
        handler.post {
            queue.removeIf { it.notificationKey == sbn.key }
        }
    }

    private fun initializeTts() {
        ttsReady = false
        val generation = ++ttsGeneration
        textToSpeech = TextToSpeech(applicationContext) { status ->
            handler.post {
                if (generation != ttsGeneration) return@post
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "TextToSpeech initialization failed: $status")
                    shutdownTts()
                    queue.clear()
                    return@post
                }
                val tts = textToSpeech ?: return@post
                tts.language = Locale.getDefault()
                tts.setAudioAttributes(SPEECH_ATTRIBUTES)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "Announcement speech started: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "Announcement speech finished: $utteranceId")
                        handler.post { finishCurrentAnnouncement(utteranceId) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.w(TAG, "Announcement speech failed: $utteranceId")
                        handler.post { finishCurrentAnnouncement(utteranceId) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.w(TAG, "Announcement speech failed: $utteranceId code=$errorCode")
                        handler.post { finishCurrentAnnouncement(utteranceId) }
                    }
                })
                ttsReady = true
                drainQueue()
            }
        }
    }

    private fun drainQueue() {
        if (!ttsReady || speaking) return
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!isStillEligible(next.packageName)) continue

            speaking = true
            currentOriginPackage = next.packageName
            currentMediaGuard = prepareMediaHandling()
            requestSpeechFocus()
            val utteranceId = "librepods-${UUID.randomUUID()}"
            currentUtteranceId = utteranceId
            val result = textToSpeech?.speak(
                next.text,
                TextToSpeech.QUEUE_FLUSH,
                Bundle(),
                utteranceId
            ) ?: TextToSpeech.ERROR
            Log.d(TAG, "Queued announcement speech result=$result package=${next.packageName}")
            if (result == TextToSpeech.ERROR) {
                finishCurrentAnnouncement(utteranceId)
            }
            return
        }
    }

    private fun isStillEligible(originPackage: String): Boolean {
        return preferences.getBoolean(PREF_ANNOUNCE_ENABLED, false) &&
            originPackage in preferences.getStringSet(PREF_ANNOUNCE_PACKAGES, emptySet()).orEmpty() &&
            ServiceManager.getService()?.isCurrentAirPodsConnected() == true
    }

    private fun prepareMediaHandling(): MediaGuard? {
        val mode = AnnouncementHandlingMode.fromPreference(
            preferences.getString(PREF_ANNOUNCE_MODE, AnnouncementHandlingMode.PAUSE.preferenceValue)
        )
        return when (mode) {
            AnnouncementHandlingMode.PAUSE -> preparePauseGuard()
            AnnouncementHandlingMode.DUCK -> prepareDuckGuard()
        }
    }

    private fun preparePauseGuard(): MediaGuard? {
        val controller = activeMediaController() ?: return null
        val state = controller.playbackState?.state ?: return null
        if (state !in ACTIVE_PLAYBACK_STATES) return null
        return PauseMediaGuard(controller).also { it.pause() }
    }

    private fun prepareDuckGuard(): MediaGuard? {
        if (!audioManager.isMusicActive) return null
        val original = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (original <= 0) return null
        val ducked = maxOf(1, (original * DUCK_FACTOR).roundToInt()).coerceAtMost(original)
        if (ducked == original) return null
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, ducked, 0)
        return DuckMediaGuard(original, ducked)
    }

    private fun activeMediaController(): MediaController? {
        val component = ComponentName(this, NotificationAnnouncementService::class.java)
        val controllers = try {
            mediaSessionManager.getActiveSessions(component)
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to query active media sessions", error)
            return null
        }
        return controllers.firstOrNull {
            it.packageName != packageName && it.playbackState?.state in ACTIVE_PLAYBACK_STATES
        }
    }

    private fun requestSpeechFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(SPEECH_ATTRIBUTES)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun finishCurrentAnnouncement(expectedUtteranceId: String? = null) {
        if (!speaking) return
        if (expectedUtteranceId != null && expectedUtteranceId != currentUtteranceId) return
        currentUtteranceId = null
        currentOriginPackage = null
        currentMediaGuard?.restoreIfSafe(
            allowPlaybackResume = ServiceManager.getService()?.isCurrentAirPodsConnected() == true
        )
        currentMediaGuard?.release()
        currentMediaGuard = null
        abandonSpeechFocus()
        speaking = false
        drainQueue()
    }

    private fun stopCurrentAnnouncement() {
        currentUtteranceId = null
        currentOriginPackage = null
        textToSpeech?.stop()
        currentMediaGuard?.restoreIfSafe(
            allowPlaybackResume = ServiceManager.getService()?.isCurrentAirPodsConnected() == true
        )
        currentMediaGuard?.release()
        currentMediaGuard = null
        abandonSpeechFocus()
        speaking = false
    }

    private fun abandonSpeechFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    private fun shutdownTts() {
        ttsGeneration++
        textToSpeech?.setOnUtteranceProgressListener(null)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
    }

    private fun buildAnnouncementText(sbn: StatusBarNotification): String? {
        val notification = sbn.notification
        val extras = notification.extras
        val content = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        ).filter { it.isNotBlank() }.distinct()
        if (content.isEmpty()) return null

        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        return "$appLabel. ${content.joinToString(". ")}"
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        stopCurrentAnnouncement()
        shutdownTts()
        dedupe.clear()
        if (::preferences.isInitialized) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
        try {
            unregisterReceiver(connectionStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already unregistered by the framework.
        }
        super.onDestroy()
    }

    private interface MediaGuard {
        fun restoreIfSafe(allowPlaybackResume: Boolean)
        fun release()
    }

    private inner class DuckMediaGuard(
        private val originalVolume: Int,
        private val featureVolume: Int
    ) : MediaGuard {
        override fun restoreIfSafe(allowPlaybackResume: Boolean) {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (VolumeRestorationPolicy.shouldRestore(current, featureVolume)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            }
        }

        override fun release() = Unit
    }

    private class PauseMediaGuard(private val controller: MediaController) : MediaGuard {
        private val restorationTracker = PauseRestorationTracker()
        private var released = false

        private val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val playbackState = state?.state ?: return
                restorationTracker.onPlaybackState(playbackState == PlaybackState.STATE_PAUSED)
            }

            override fun onSessionDestroyed() {
                restorationTracker.onSessionDestroyed()
            }
        }

        fun pause() {
            controller.registerCallback(callback)
            restorationTracker.onPauseIssued()
            controller.transportControls.pause()
            restorationTracker.onPlaybackState(
                controller.playbackState?.state == PlaybackState.STATE_PAUSED
            )
        }

        override fun restoreIfSafe(allowPlaybackResume: Boolean) {
            if (!allowPlaybackResume) return
            val state = controller.playbackState?.state
            if (restorationTracker.shouldResume(state == PlaybackState.STATE_PAUSED)) {
                controller.transportControls.play()
            }
        }

        override fun release() {
            if (released) return
            released = true
            controller.unregisterCallback(callback)
        }
    }

    companion object {
        private const val TAG = "NotificationAnnouncer"
        const val PREFS_NAME = "settings"
        const val PREF_ANNOUNCE_ENABLED = "announce_notifications_enabled"
        const val PREF_ANNOUNCE_PACKAGES = "notification_announcement_packages"
        const val PREF_ANNOUNCE_MODE = "notification_announcement_mode"
        private const val MAX_QUEUE_SIZE = 20
        private const val DUCK_FACTOR = 0.35f
        private val ANNOUNCEMENT_PREFERENCE_KEYS = setOf(
            PREF_ANNOUNCE_ENABLED,
            PREF_ANNOUNCE_PACKAGES,
            PREF_ANNOUNCE_MODE
        )
        private val ACTIVE_PLAYBACK_STATES = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING
        )
        private val SPEECH_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
