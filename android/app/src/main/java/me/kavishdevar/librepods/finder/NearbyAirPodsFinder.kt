package me.kavishdevar.librepods.finder

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NearbyFinderStatus {
    STOPPED,
    WAITING_FOR_SIGNAL,
    ACTIVE,
    PERMISSION_REQUIRED,
    BLUETOOTH_OFF,
    NO_SELECTED_DEVICE,
    ERROR
}

data class NearbyFinderState(
    val running: Boolean = false,
    val status: NearbyFinderStatus = NearbyFinderStatus.STOPPED,
    val signal: FinderSignalSnapshot = FinderSignalSnapshot(),
    val errorMessage: String? = null
)

/**
 * Finder coordinator. It deliberately does not own a scanner: LibrePods already performs a
 * verified, AirPods-specific BLE scan, so this consumes those RSSI callbacks only while active.
 */
class NearbyAirPodsFinder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hasSelectedDevice: () -> Boolean,
    private val processor: RssiSignalProcessor = RssiSignalProcessor()
) {
    private val _state = MutableStateFlow(NearbyFinderState())
    val state: StateFlow<NearbyFinderState> = _state
    private val targetLock = Any()
    private var tickerJob: Job? = null
    private var trackedAddress: String? = null
    private var trackedRssi = Int.MIN_VALUE
    private var trackedLastSeen = 0L
    private var trackedIdentityVerified = false

    fun start(): Boolean {
        if (_state.value.running) return true
        when {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED -> {
                _state.value = NearbyFinderState(status = NearbyFinderStatus.PERMISSION_REQUIRED)
                return false
            }
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED -> {
                _state.value = NearbyFinderState(status = NearbyFinderStatus.PERMISSION_REQUIRED)
                return false
            }
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> {
                _state.value = NearbyFinderState(status = NearbyFinderStatus.PERMISSION_REQUIRED)
                return false
            }
            context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled != true -> {
                _state.value = NearbyFinderState(status = NearbyFinderStatus.BLUETOOTH_OFF)
                return false
            }
            !hasSelectedDevice() -> {
                _state.value = NearbyFinderState(status = NearbyFinderStatus.NO_SELECTED_DEVICE)
                return false
            }
        }

        synchronized(targetLock) {
            processor.reset()
            trackedAddress = null
            trackedRssi = Int.MIN_VALUE
            trackedLastSeen = 0L
            trackedIdentityVerified = false
            _state.value = NearbyFinderState(
                running = true,
                status = NearbyFinderStatus.WAITING_FOR_SIGNAL,
                signal = processor.snapshot(SystemClock.elapsedRealtime())
            )
        }
        tickerJob = scope.launch {
            while (true) {
                delay(250L)
                val now = SystemClock.elapsedRealtime()
                synchronized(targetLock) {
                    if (!_state.value.running) return@launch
                    val snapshot = processor.snapshot(now)
                    _state.update { previous ->
                        if (!previous.running) previous else previous.copy(
                            status = if (snapshot.proximity == ProximityBucket.SIGNAL_LOST) {
                                NearbyFinderStatus.WAITING_FOR_SIGNAL
                            } else {
                                NearbyFinderStatus.ACTIVE
                            },
                            signal = snapshot
                        )
                    }
                }
            }
        }
        return true
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        synchronized(targetLock) {
            processor.reset()
            trackedAddress = null
            trackedRssi = Int.MIN_VALUE
            trackedLastSeen = 0L
            trackedIdentityVerified = false
            _state.value = NearbyFinderState()
        }
    }

    fun onVerifiedScanRssi(
        device: BluetoothDevice,
        rssi: Int,
        selectedDeviceIdentityVerified: Boolean
    ) {
        synchronized(targetLock) {
            if (!_state.value.running) return
            val now = SystemClock.elapsedRealtime()
            val previousAddress = trackedAddress
            val changingTarget = previousAddress != null && previousAddress != device.address
            if (changingTarget) {
                val previousIsStale = now - trackedLastSeen >= TARGET_STALE_MS
                // Never let an anonymous separated advertisement steal the finder from the
                // IRK-verified selected AirPods while that owned signal is still fresh.
                if (trackedIdentityVerified && !selectedDeviceIdentityVerified && !previousIsStale) {
                    return
                }
                val candidateHasStrongerIdentity =
                    selectedDeviceIdentityVerified && !trackedIdentityVerified
                val candidateIsMuchStronger = rssi >= trackedRssi + TARGET_SWITCH_MARGIN_DB
                if (!previousIsStale && !candidateHasStrongerIdentity && !candidateIsMuchStronger) {
                    return
                }
                processor.reset()
            }
            trackedAddress = device.address
            trackedRssi = rssi
            trackedLastSeen = now
            trackedIdentityVerified = selectedDeviceIdentityVerified
            val snapshot = processor.addSample(rssi = rssi, elapsedRealtime = now)
            _state.update { previous ->
                previous.copy(
                    status = if (previous.signal.rawRssi == null) {
                        NearbyFinderStatus.ACTIVE
                    } else {
                        previous.status
                    },
                    signal = if (previous.signal.rawRssi == null) snapshot else previous.signal,
                    errorMessage = null
                )
            }
        }
    }

    fun onScanError(errorCode: Int) {
        synchronized(targetLock) {
            if (!_state.value.running) return
            tickerJob?.cancel()
            tickerJob = null
            val snapshot = processor.snapshot(SystemClock.elapsedRealtime())
            _state.update { previous ->
                previous.copy(
                    running = false,
                    status = NearbyFinderStatus.ERROR,
                    signal = snapshot,
                    errorMessage = "AirPods BLE scan failed (code $errorCode)."
                )
            }
        }
    }

    fun refreshPrerequisites(): Boolean {
        if (_state.value.status == NearbyFinderStatus.PERMISSION_REQUIRED ||
            _state.value.status == NearbyFinderStatus.BLUETOOTH_OFF ||
            _state.value.status == NearbyFinderStatus.NO_SELECTED_DEVICE
        ) {
            return start()
        }
        return false
    }

    private companion object {
        const val TARGET_STALE_MS = 5_000L
        const val TARGET_SWITCH_MARGIN_DB = 10
    }
}
