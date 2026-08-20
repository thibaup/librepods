package me.kavishdevar.librepods.features.findmy

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.BuildConfig

class FindMyViewModel(application: Application) : AndroidViewModel(application) {
    private val client = AppleFindMyClient(application)
    private val _uiState = MutableStateFlow(FindMyUiState())
    val uiState: StateFlow<FindMyUiState> = _uiState.asStateFlow()
    private val operationMutex = Mutex()
    private var phaseBeforeError = FindMyPhase.SIGNED_OUT

    init {
        restoreSavedSession()
    }

    fun retrySavedSession() {
        if (_uiState.value.phase == FindMyPhase.RESTORING ||
            _uiState.value.phase == FindMyPhase.SIGNING_IN ||
            _uiState.value.phase == FindMyPhase.REFRESHING
        ) return
        restoreSavedSession()
    }

    private fun restoreSavedSession() {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Apple restore begin: saved=${client.appleId.isNotBlank()}")
        }
        _uiState.value = _uiState.value.copy(
            phase = FindMyPhase.RESTORING,
            appleId = client.appleId,
            errorMessage = null,
            message = null,
        )
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    operationMutex.withLock { client.restore() }
                }
                if (BuildConfig.DEBUG) Log.i(TAG, "Apple restore result: ${result.traceLabel()}")
                applyAuthResult(result)
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Apple account restore failed: ${error.message}")
                phaseBeforeError = FindMyPhase.SESSION_ERROR
                _uiState.value = _uiState.value.copy(
                    phase = FindMyPhase.SESSION_ERROR,
                    appleId = client.appleId,
                    errorMessage = error.message ?: "Could not restore the Find My session.",
                )
            }
        }
    }

    fun signIn(appleId: String, password: String) {
        runOperation(FindMyPhase.SIGNING_IN) {
            applyAuthResult(client.signIn(appleId, password))
        }
    }

    fun submitTwoFactor(code: String) {
        runOperation(FindMyPhase.SIGNING_IN) {
            val devices = client.submitTwoFactor(code)
            setReady(devices)
        }
    }

    fun requestNewTwoFactorCode() {
        runOperation(FindMyPhase.SIGNING_IN) {
            client.requestNewTwoFactorCode()
            _uiState.value = _uiState.value.copy(
                phase = FindMyPhase.NEEDS_TWO_FACTOR,
                errorMessage = null,
                message = "A new verification code was requested.",
            )
        }
    }

    fun refresh() {
        runOperation(FindMyPhase.REFRESHING) {
            setReady(client.refreshDevices(waitForFreshLocation = true))
        }
    }

    /**
     * FMIP exposes an account refresh rather than a proven single-device endpoint. Refresh the
     * account, but keep the spinner and user intent scoped to the requested row.
     */
    fun refreshDevice(deviceId: String) {
        if (deviceId.isBlank() || _uiState.value.refreshingDeviceIds.isNotEmpty() ||
            _uiState.value.phase == FindMyPhase.SIGNING_IN ||
            _uiState.value.phase == FindMyPhase.REFRESHING
        ) return
        _uiState.value = _uiState.value.copy(
            refreshingDeviceIds = setOf(deviceId),
            errorMessage = null,
            message = null,
        )
        viewModelScope.launch {
            try {
                val devices = withContext(Dispatchers.IO) {
                    operationMutex.withLock {
                        client.refreshDevices(
                            waitForFreshLocation = true,
                            targetDeviceId = deviceId,
                        )
                    }
                }
                setReady(devices)
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    refreshingDeviceIds = emptySet(),
                    errorMessage = error.message ?: "Could not refresh this Find My device.",
                )
            }
        }
    }

    fun signOut() {
        viewModelScope.launch(Dispatchers.IO) {
            operationMutex.withLock { client.signOut() }
            _uiState.value = FindMyUiState(phase = FindMyPhase.SIGNED_OUT)
        }
    }

    fun dismissError() {
        _uiState.value = if (_uiState.value.phase == FindMyPhase.ERROR) {
            _uiState.value.copy(phase = phaseBeforeError, errorMessage = null)
        } else {
            _uiState.value.copy(errorMessage = null)
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun runOperation(phase: FindMyPhase, operation: suspend () -> Unit) {
        if (_uiState.value.phase == FindMyPhase.SIGNING_IN ||
            _uiState.value.phase == FindMyPhase.REFRESHING ||
            _uiState.value.refreshingDeviceIds.isNotEmpty()
        ) return
        val stateBeforeOperation = _uiState.value
        phaseBeforeError = when {
            stateBeforeOperation.devices.isNotEmpty() -> FindMyPhase.READY
            stateBeforeOperation.phase == FindMyPhase.NEEDS_TWO_FACTOR ->
                FindMyPhase.NEEDS_TWO_FACTOR
            stateBeforeOperation.phase == FindMyPhase.SIGNED_OUT -> FindMyPhase.SIGNED_OUT
            client.appleId.isNotBlank() -> FindMyPhase.SESSION_ERROR
            else -> FindMyPhase.SIGNED_OUT
        }
        _uiState.value = _uiState.value.copy(
            phase = phase,
            errorMessage = null,
            message = null,
        )
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    operationMutex.withLock { operation() }
                }
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    phase = FindMyPhase.ERROR,
                    errorMessage = error.message ?: "Find My failed.",
                )
            }
        }
    }

    private fun applyAuthResult(result: FindMyAuthResult) {
        if (BuildConfig.DEBUG) Log.i(TAG, "Apple auth state: ${result.traceLabel()}")
        when (result) {
            FindMyAuthResult.SignedOut -> {
                _uiState.value = FindMyUiState(phase = FindMyPhase.SIGNED_OUT)
            }
            FindMyAuthResult.NeedsTwoFactor -> {
                _uiState.value = FindMyUiState(
                    phase = FindMyPhase.NEEDS_TWO_FACTOR,
                    appleId = client.appleId,
                )
            }
            is FindMyAuthResult.Ready -> setReady(result.devices)
        }
    }

    private fun setReady(devices: List<FindMyDevice>) {
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "Apple account ready: " + devices.joinToString { device ->
                    "${device.name}(${device.id.takeLast(8)}):${device.location?.timestampMillis ?: "none"}"
                },
            )
        }
        _uiState.value = FindMyUiState(
            phase = FindMyPhase.READY,
            appleId = client.appleId,
            devices = devices,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val TAG = "FindMyTrace"
    }
}

private fun FindMyAuthResult.traceLabel(): String = when (this) {
    FindMyAuthResult.SignedOut -> "signed_out"
    FindMyAuthResult.NeedsTwoFactor -> "needs_two_factor"
    is FindMyAuthResult.Ready -> "ready(${devices.size} devices)"
}
