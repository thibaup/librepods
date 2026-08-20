/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.services

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.bluetooth.HeartRateSample
import me.kavishdevar.librepods.bluetooth.HeartRateStartupStrategy
import me.kavishdevar.librepods.bluetooth.HostLibHidInitializationOutcome

internal fun shouldRetryHeartRateInCurrentAacpSession(
    hostLibHidAdvertised: Boolean
): Boolean = hostLibHidAdvertised

/**
 * Owns the heart-rate stream lifecycle and its user-visible state.
 *
 * A single coroutine performs startup, warm-up, live sample collection, and in-stream retries.
 * RTBuddy callbacks only enqueue validated samples; they do not mutate status or run watchdogs.
 */
internal class HeartRateMonitor(
    private val scope: CoroutineScope,
    initiallyEnabled: Boolean,
    private val isTransportReady: () -> Boolean,
    private val isAirPodsWorn: () -> Boolean,
    private val beforeFirstStart: suspend () -> Unit,
    private val sendConnectService0: () -> Boolean,
    private val sendCapabilitiesService0: () -> Boolean,
    private val sendConnectService4: () -> Boolean,
    private val sendCapabilitiesService4: () -> Boolean,
    private val awaitHeartRateService: suspend () -> Boolean,
    private val isHostLibHidAdvertised: () -> Boolean,
    private val isHeartRateServiceMetadataResolved: () -> Boolean,
    private val initializeHostLibHid: suspend () -> HostLibHidInitializationOutcome,
    private val enableHeartRate: () -> Boolean,
    private val sendStart: () -> Boolean,
    private val sendStop: () -> Unit,
    private val requestTransportRecovery: () -> Boolean,
    private val beginDiagnosticAttempt: (Int, HeartRateStartupStrategy) -> Unit,
    private val logDiagnosticCheckpoint: (String) -> Unit,
    private val onPublishedSample: (HeartRateSample) -> Unit
) {
    private enum class RefreshReason(val diagnosticName: String) {
        FIRST_SAMPLE_TIMEOUT("first-sample-timeout"),
        STREAM_STALLED("stream-stalled")
    }

    private data class RefreshWindow(
        val reason: RefreshReason,
        var deadlineElapsedRealtime: Long,
        var attempts: Int = 0
    )

    private val lock = Any()
    private val incomingSamples = Channel<HeartRateSample>(Channel.UNLIMITED)
    private var monitoringJob: Job? = null
    private var sessionNeedsStop = false
    private var acceptingSamples = false

    private val _state = MutableStateFlow(
        HeartRateMonitoringState(
            enabled = initiallyEnabled,
            status = if (initiallyEnabled) {
                HeartRateMonitoringStatus.WAITING_FOR_AIRPODS
            } else {
                HeartRateMonitoringStatus.OFF
            }
        )
    )
    val state: StateFlow<HeartRateMonitoringState> = _state

    fun setEnabled(enabled: Boolean) {
        val wasEnabled = state.value.enabled
        val transportReady = isTransportReady()
        val airPodsWorn = isAirPodsWorn()
        updateState {
            it.copy(
                enabled = enabled,
                status = when {
                    !enabled -> HeartRateMonitoringStatus.OFF
                    !transportReady -> HeartRateMonitoringStatus.WAITING_FOR_AIRPODS
                    !airPodsWorn -> HeartRateMonitoringStatus.WAITING_TO_BE_WORN
                    !wasEnabled -> HeartRateMonitoringStatus.STARTING
                    else -> it.status
                }
            )
        }

        if (enabled) startIfPossible() else stop(forceStop = wasEnabled)
    }

    fun startIfPossible() {
        val currentState = state.value
        if (!currentState.enabled) {
            updateStatus(HeartRateMonitoringStatus.OFF)
            return
        }
        if (!isTransportReady()) {
            updateStatus(HeartRateMonitoringStatus.WAITING_FOR_AIRPODS)
            return
        }
        if (!isAirPodsWorn()) {
            updateStatus(HeartRateMonitoringStatus.WAITING_TO_BE_WORN)
            return
        }

        val job = synchronized(lock) {
            if (monitoringJob?.isActive == true) return

            updateStatus(HeartRateMonitoringStatus.STARTING)
            scope.launch(start = CoroutineStart.LAZY) { runMonitoringLoop() }
                .also { monitoringJob = it }
        }
        job.start()
    }

    fun onValidatedSample(sample: HeartRateSample) {
        val accepted = synchronized(lock) {
            state.value.enabled && acceptingSamples && isTransportReady()
        }
        if (accepted) incomingSamples.trySend(sample)
    }

    fun markReconnecting() {
        if (state.value.enabled) updateStatus(HeartRateMonitoringStatus.RECONNECTING)
    }

    fun onWearStateChanged(isWorn: Boolean) {
        if (isWorn) {
            startIfPossible()
        } else {
            stopAndUpdateStatus(
                forceStop = false,
                sendStopFrame = true,
                enabledStatus = HeartRateMonitoringStatus.WAITING_TO_BE_WORN
            )
        }
    }

    fun stop(forceStop: Boolean = false, sendStopFrame: Boolean = true) {
        stopAndUpdateStatus(
            forceStop = forceStop,
            sendStopFrame = sendStopFrame,
            enabledStatus = HeartRateMonitoringStatus.WAITING_FOR_AIRPODS
        )
    }

    private fun stopAndUpdateStatus(
        forceStop: Boolean,
        sendStopFrame: Boolean,
        enabledStatus: HeartRateMonitoringStatus
    ) {
        synchronized(lock) {
            val jobWasActive = monitoringJob?.isActive == true
            monitoringJob?.cancel()
            monitoringJob = null
            stopSessionLocked(forceStop || jobWasActive, sendStopFrame)
            drainIncomingSamples()
        }
        updateStatus(
            if (state.value.enabled) {
                enabledStatus
            } else {
                HeartRateMonitoringStatus.OFF
            }
        )
    }

    private suspend fun runMonitoringLoop() {
        val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        var refreshWindow: RefreshWindow? = null
        var diagnosticAttemptNumber = 0

        try {
            beforeFirstStart()

            while (canRun()) {
                updateStatus(
                    if (refreshWindow == null) {
                        HeartRateMonitoringStatus.STARTING
                    } else {
                        HeartRateMonitoringStatus.RECONNECTING
                    }
                )

                diagnosticAttemptNumber++
                val strategy = HeartRateStartupStrategy.forAttempt(diagnosticAttemptNumber)
                val attemptStartedAt = startStreamAttempt(
                    attemptNumber = diagnosticAttemptNumber,
                    strategy = strategy
                )
                if (!canRun()) return
                if (attemptStartedAt != null) {
                    refreshWindow?.deadlineElapsedRealtime =
                        attemptStartedAt + FIRST_SAMPLE_TIMEOUT_MILLIS
                }

                val failure = if (attemptStartedAt == null) {
                    RefreshReason.FIRST_SAMPLE_TIMEOUT
                } else {
                    awaitStreamFailure(
                        attemptStartedAt = attemptStartedAt,
                        refreshDeadline = refreshWindow?.deadlineElapsedRealtime,
                        attemptNumber = diagnosticAttemptNumber,
                        strategy = strategy,
                        onStreamStarted = { refreshWindow = null }
                    ) ?: return
                }

                synchronized(lock) { stopSessionLocked() }
                if (!canRun()) return
                val window = refreshWindow ?: RefreshWindow(
                    reason = failure,
                    deadlineElapsedRealtime =
                        SystemClock.elapsedRealtime() + RECONNECT_WINDOW_MILLIS
                ).also {
                    refreshWindow = it
                    Log.i(
                        TAG,
                        "RTBuddy heart-rate refresh requested " +
                            "reason=${it.reason.diagnosticName} transport=healthy"
                    )
                }

                if (!shouldRetryHeartRateInCurrentAacpSession(isHostLibHidAdvertised())) {
                    Log.w(
                        TAG,
                        "HR_DIAG same-session-retry skipped=true " +
                            "reason=hostlib-not-advertised " +
                            "metadataResolved=${isHeartRateServiceMetadataResolved()}"
                    )
                    requestRecoveryOrFinish()
                    return
                }

                if (!waitForRetry(window)) {
                    Log.w(
                        TAG,
                        "RTBuddy heart-rate refresh failed " +
                            "reason=${window.reason.diagnosticName} attempts=${window.attempts}"
                    )
                    requestRecoveryOrFinish()
                    return
                }
            }
        } finally {
            synchronized(lock) {
                if (monitoringJob === currentJob) {
                    stopSessionLocked()
                    monitoringJob = null
                }
            }
        }
    }

    private suspend fun startStreamAttempt(
        attemptNumber: Int,
        strategy: HeartRateStartupStrategy
    ): Long? {
        drainIncomingSamples()
        beginDiagnosticAttempt(attemptNumber, strategy)
        Log.i(
            TAG,
            "HR_DIAG attempt-start id=$attemptNumber strategy=${strategy.diagnosticName} " +
                "transport=${isTransportReady()} worn=${isAirPodsWorn()}"
        )
        if (!initializeAacpSession(attemptNumber, strategy)) {
            logDiagnosticCheckpoint("aacp-init-failed")
            return null
        }
        if (!awaitHeartRateService()) {
            logDiagnosticCheckpoint("service-resolution-failed")
            return null
        }
        if (strategy == HeartRateStartupStrategy.HOSTLIB_ASSISTED) {
            val hostLibInitialization = initializeHostLibHid()
            if (!hostLibInitialization.canContinue) {
                logDiagnosticCheckpoint("hostlib-send-failed")
                return null
            }
        } else {
            Log.i(TAG, "HR_DIAG hostlib-init strategy=direct skipped=true")
        }
        val enabled = synchronized(lock) {
            canRun() && enableHeartRate().also { sent ->
                if (sent) sessionNeedsStop = true
            }
        }
        Log.i(TAG, "HR_DIAG hrm-state sent=$enabled")
        if (!enabled) {
            logDiagnosticCheckpoint("hrm-state-send-failed")
            return null
        }

        delay(START_COMMAND_DELAY_MILLIS)

        return synchronized(lock) {
            if (!canRun()) {
                null
            } else {
                val startedAt = SystemClock.elapsedRealtime()
                val started = sendStart()
                acceptingSamples = started
                sessionNeedsStop = sessionNeedsStop || started
                Log.i(
                    TAG,
                    "HR_DIAG start-dispatch id=$attemptNumber " +
                        "strategy=${strategy.diagnosticName} sent=$started"
                )
                if (!started) logDiagnosticCheckpoint("start-send-failed")
                startedAt.takeIf { started }
            }
        }
    }

    private suspend fun initializeAacpSession(
        attemptNumber: Int,
        strategy: HeartRateStartupStrategy
    ): Boolean {
        val frames = listOf(
            Triple("connect-service-0", sendConnectService0, 180L),
            Triple("capabilities-service-0", sendCapabilitiesService0, 220L),
            Triple("connect-service-4", sendConnectService4, 180L),
            Triple("capabilities-service-4", sendCapabilitiesService4, 220L)
        )

        for ((stage, sendFrame, delayAfter) in frames) {
            val startedAt = SystemClock.elapsedRealtime()
            val sent = sendIfRunning(sendFrame)
            Log.i(
                TAG,
                "HR_DIAG init-frame id=$attemptNumber strategy=${strategy.diagnosticName} " +
                    "stage=$stage sent=$sent elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            if (!sent) return false
            delay(delayAfter)
        }

        Log.d(TAG, "RTBuddy heart-rate AACP 1.3 session initialized")
        return canRun()
    }

    private suspend fun awaitStreamFailure(
        attemptStartedAt: Long,
        refreshDeadline: Long?,
        attemptNumber: Int,
        strategy: HeartRateStartupStrategy,
        onStreamStarted: () -> Unit
    ): RefreshReason? {
        var warmupSamplesRemaining = WARMUP_SAMPLE_COUNT
        var streamStarted = false
        var validatedSampleCount = 0
        var publishedSampleCount = 0
        var previousSampleElapsedRealtime: Long? = null
        val firstSampleDeadline = refreshDeadline
            ?: (attemptStartedAt + FIRST_SAMPLE_TIMEOUT_MILLIS)

        while (canRun()) {
            val timeout = if (streamStarted) {
                STALL_TIMEOUT_MILLIS
            } else {
                (firstSampleDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            }
            if (timeout == 0L) {
                Log.w(
                    TAG,
                    "HR_DIAG stream-failure reason=first-sample-timeout " +
                        "elapsedFromStartMs=${SystemClock.elapsedRealtime() - attemptStartedAt}"
                )
                logDiagnosticCheckpoint("first-sample-timeout")
                return RefreshReason.FIRST_SAMPLE_TIMEOUT
            }

            val sample = withTimeoutOrNull(timeout) { incomingSamples.receive() }
            if (sample == null) {
                val reason = if (streamStarted) {
                    RefreshReason.STREAM_STALLED
                } else {
                    RefreshReason.FIRST_SAMPLE_TIMEOUT
                }
                Log.w(
                    TAG,
                    "HR_DIAG stream-failure reason=${reason.diagnosticName} " +
                        "elapsedFromStartMs=${SystemClock.elapsedRealtime() - attemptStartedAt}"
                )
                logDiagnosticCheckpoint(reason.diagnosticName)
                return reason
            }

            if (!canRun()) return null
            validatedSampleCount++
            val intervalMillis = previousSampleElapsedRealtime?.let {
                sample.receivedAtElapsedRealtime - it
            }
            previousSampleElapsedRealtime = sample.receivedAtElapsedRealtime
            if (validatedSampleCount <= SAMPLE_DIAGNOSTIC_INITIAL_COUNT ||
                validatedSampleCount % SAMPLE_DIAGNOSTIC_PERIOD == 0
            ) {
                Log.i(
                    TAG,
                    "HR_DIAG sample-flow id=$attemptNumber " +
                        "strategy=${strategy.diagnosticName} validated=$validatedSampleCount " +
                        "intervalMs=${intervalMillis ?: -1} " +
                        "warmupRemaining=$warmupSamplesRemaining published=$publishedSampleCount"
                )
            }
            if (!streamStarted) {
                streamStarted = true
                Log.i(
                    TAG,
                    "HR_DIAG first-sample received=true " +
                        "elapsedFromStartMs=${sample.receivedAtElapsedRealtime - attemptStartedAt}"
                )
                logDiagnosticCheckpoint("first-sample")
                if (refreshDeadline != null) {
                    Log.i(TAG, "RTBuddy heart-rate reconnect succeeded")
                }
                onStreamStarted()
            }
            if (warmupSamplesRemaining > 0) {
                warmupSamplesRemaining--
                updateStatus(HeartRateMonitoringStatus.CALIBRATING)
                continue
            }

            publish(sample)
            publishedSampleCount++
            if (publishedSampleCount == 1) {
                Log.i(
                    TAG,
                    "HR_DIAG first-published-sample id=$attemptNumber " +
                        "strategy=${strategy.diagnosticName} validated=$validatedSampleCount"
                )
                logDiagnosticCheckpoint("first-published-sample")
            }
            updateStatus(HeartRateMonitoringStatus.LIVE)
        }
        return null
    }

    private fun waitForRetry(window: RefreshWindow): Boolean {
        if (window.attempts >= MAX_RECONNECT_ATTEMPTS) return false

        window.attempts++
        updateStatus(HeartRateMonitoringStatus.RECONNECTING)
        Log.w(
            TAG,
            "RTBuddy heart-rate reconnect attempt=${window.attempts} " +
                "reason=${window.reason.diagnosticName} timeout=${FIRST_SAMPLE_TIMEOUT_MILLIS}ms"
        )
        return canRun()
    }

    private fun requestRecoveryOrFinish() {
        updateStatus(HeartRateMonitoringStatus.RECONNECTING)
        if (requestTransportRecovery()) {
            Log.i(TAG, "Requesting one automatic AACP rebuild for heart-rate recovery")
        } else if (canRun()) {
            updateStatus(HeartRateMonitoringStatus.COULDNT_START)
            Log.i(TAG, "Automatic AACP rebuild unavailable; waiting for manual Retry")
        }
    }

    private fun publish(sample: HeartRateSample) {
        updateState { current ->
            current.copy(samples = (current.samples + sample).takeLast(MAX_SAMPLES))
        }
        onPublishedSample(sample)
    }

    private fun sendIfRunning(sendFrame: () -> Boolean): Boolean = synchronized(lock) {
        canRun() && sendFrame()
    }

    private fun stopSessionLocked(
        forceStop: Boolean = false,
        sendStopFrame: Boolean = true
    ) {
        val shouldStop = forceStop || sessionNeedsStop || acceptingSamples
        sessionNeedsStop = false
        acceptingSamples = false
        if (sendStopFrame && shouldStop && isTransportReady()) sendStop()
    }

    private fun canRun(): Boolean =
        state.value.enabled && isTransportReady() && isAirPodsWorn()

    private fun updateStatus(status: HeartRateMonitoringStatus) {
        updateState { it.copy(status = status) }
    }

    private inline fun updateState(
        transform: (HeartRateMonitoringState) -> HeartRateMonitoringState
    ) {
        synchronized(lock) {
            _state.value = transform(_state.value)
        }
    }

    private fun drainIncomingSamples() {
        while (incomingSamples.tryReceive().isSuccess) Unit
    }

    private companion object {
        const val TAG = "HeartRateMonitor"
        const val MAX_SAMPLES = 60
        const val FIRST_SAMPLE_TIMEOUT_MILLIS = 8_000L
        const val RECONNECT_WINDOW_MILLIS = FIRST_SAMPLE_TIMEOUT_MILLIS
        const val STALL_TIMEOUT_MILLIS = 5_000L
        const val START_COMMAND_DELAY_MILLIS = 120L
        const val WARMUP_SAMPLE_COUNT = 4
        const val MAX_RECONNECT_ATTEMPTS = 1
        const val SAMPLE_DIAGNOSTIC_INITIAL_COUNT = 8
        const val SAMPLE_DIAGNOSTIC_PERIOD = 10
    }
}
