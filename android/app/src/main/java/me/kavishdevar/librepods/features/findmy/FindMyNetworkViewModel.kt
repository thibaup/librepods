package me.kavishdevar.librepods.features.findmy

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.BuildConfig

class FindMyNetworkViewModel(application: Application) : AndroidViewModel(application) {
    private val client = FindMyNetworkClient(application)
    private val _uiState = MutableStateFlow(FindMyNetworkUiState())
    val uiState: StateFlow<FindMyNetworkUiState> = _uiState.asStateFlow()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appleIdHint = ""
    private var pendingExportUri: Uri? = null

    init {
        restore()
    }

    fun setAppleIdHint(value: String) {
        appleIdHint = value
        if (_uiState.value.appleId.isBlank() &&
            _uiState.value.phase == FindMyNetworkPhase.SIGNED_OUT
        ) {
            _uiState.value = _uiState.value.copy(appleId = value)
        }
    }

    fun retryRestore() = restore()

    private fun restore() {
        if (_uiState.value.phase == FindMyNetworkPhase.RESTORING &&
            _uiState.value.errorMessage == null &&
            _uiState.value.appleId.isNotBlank()
        ) return
        _uiState.value = _uiState.value.copy(
            phase = FindMyNetworkPhase.RESTORING,
            errorMessage = null,
        )
        viewModelScope.launch {
            try {
                when (val restored = client.restore()) {
                    FindMyNetworkRestoreResult.SignedOut -> {
                        _uiState.value = _uiState.value.copy(
                            phase = FindMyNetworkPhase.SIGNED_OUT,
                            appleId = appleIdHint,
                        )
                    }
                    is FindMyNetworkRestoreResult.Ready -> {
                        _uiState.value = _uiState.value.copy(
                            phase = if (restored.hasAccessories) {
                                FindMyNetworkPhase.REFRESHING_REPORTS
                            } else {
                                FindMyNetworkPhase.READY_TO_RECOVER
                            },
                            appleId = client.currentAppleId,
                        )
                        if (restored.hasAccessories) refreshReportsInternal()
                    }
                }
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Find My network restore failed: ${error.message}")
                }
                _uiState.value = _uiState.value.copy(
                    phase = FindMyNetworkPhase.ERROR,
                    appleId = client.currentAppleId.ifBlank { appleIdHint },
                    errorMessage = safeMessage(error, "Could not restore Find My network."),
                )
            }
            prepareAnisette()
        }
    }

    private fun prepareAnisette() {
        if (_uiState.value.anisetteState == FindMyNetworkAnisetteState.CHECKING ||
            _uiState.value.anisetteState == FindMyNetworkAnisetteState.LOCAL_READY
        ) return
        _uiState.value = _uiState.value.copy(
            anisetteState = FindMyNetworkAnisetteState.CHECKING,
            anisetteDetail = null,
        )
        viewModelScope.launch {
            try {
                val result = client.checkAnisette()
                _uiState.value = _uiState.value.copy(
                    anisetteState = if (result.local) {
                        FindMyNetworkAnisetteState.LOCAL_READY
                    } else {
                        FindMyNetworkAnisetteState.REMOTE_FALLBACK
                    },
                    anisetteDetail = result.detail,
                )
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    anisetteState = FindMyNetworkAnisetteState.REMOTE_FALLBACK,
                    anisetteDetail = safeMessage(error, "On-device Anisette is unavailable."),
                )
            }
        }
    }

    fun signIn(appleId: String, password: String) {
        runOperation(
            phase = FindMyNetworkPhase.SIGNING_IN,
            fallback = FindMyNetworkPhase.SIGNED_OUT,
        ) {
            when (val result = client.login(appleId, password)) {
                is FindMyNetworkLoginResult.NeedsTwoFactor -> {
                    _uiState.value = _uiState.value.copy(
                        phase = FindMyNetworkPhase.CHOOSE_TWO_FACTOR_METHOD,
                        appleId = appleId.trim(),
                        twoFactorMethods = result.methods,
                    )
                }
                FindMyNetworkLoginResult.Ready -> signedInReadyToRecover(appleId)
            }
        }
    }

    fun chooseTwoFactorMethod(index: Int) {
        runOperation(
            phase = FindMyNetworkPhase.SIGNING_IN,
            fallback = FindMyNetworkPhase.CHOOSE_TWO_FACTOR_METHOD,
        ) {
            client.requestTwoFactorCode(index)
            _uiState.value = _uiState.value.copy(
                phase = FindMyNetworkPhase.ENTER_TWO_FACTOR_CODE,
                selectedTwoFactorMethod = index,
            )
        }
    }

    fun submitTwoFactorCode(code: String) {
        val method = _uiState.value.selectedTwoFactorMethod ?: return
        runOperation(
            phase = FindMyNetworkPhase.SIGNING_IN,
            fallback = FindMyNetworkPhase.ENTER_TWO_FACTOR_CODE,
        ) {
            client.submitTwoFactorCode(method, code)
            signedInReadyToRecover(_uiState.value.appleId)
        }
    }

    fun openRecovery() {
        runOperation(
            phase = FindMyNetworkPhase.OPENING_RECOVERY,
            fallback = readyPhase(),
        ) {
            val devices = client.openRecovery()
            _uiState.value = _uiState.value.copy(
                phase = FindMyNetworkPhase.CHOOSE_RECOVERY_DEVICE,
                recoveryDevices = devices,
                selectedRecoverySerial = null,
                passcodeAttempts = 0,
            )
        }
    }

    fun selectRecoveryDevice(serial: String?) {
        _uiState.value = _uiState.value.copy(selectedRecoverySerial = serial)
    }

    fun submitRecoveryPasscode(passcode: String) {
        val serial = _uiState.value.selectedRecoverySerial ?: return
        val attempts = _uiState.value.passcodeAttempts
        if (attempts >= FIND_MY_MAX_PASSCODE_ATTEMPTS) return
        _uiState.value = _uiState.value.copy(
            phase = FindMyNetworkPhase.UNLOCKING_KEYCHAIN,
            errorMessage = null,
        )
        viewModelScope.launch {
            try {
                val candidates = client.unlockAndFetch(serial, passcode)
                _uiState.value = _uiState.value.copy(
                    phase = FindMyNetworkPhase.CHOOSE_ACCESSORIES,
                    candidates = candidates,
                    selectedBeaconIds = candidates.mapTo(linkedSetOf()) { it.beaconId },
                    selectedRecoverySerial = null,
                )
            } catch (error: FindMyNetworkException) {
                val decision = decideRecoveryFailure(error.reason, attempts)
                if (decision.stopSession) {
                    client.closeRecovery()
                }
                _uiState.value = _uiState.value.copy(
                    phase = if (decision.stopSession) {
                        readyPhase()
                    } else {
                        FindMyNetworkPhase.CHOOSE_RECOVERY_DEVICE
                    },
                    selectedRecoverySerial = if (decision.clearDeviceSelection) null else serial,
                    passcodeAttempts = decision.nextAttempts,
                    errorMessage = if (decision.stoppedAfterLimit) {
                        "Keychain recovery was stopped after three attempts. " +
                            "Start a new recovery session before trying again."
                    } else {
                        safeMessage(error, "Could not unlock the Apple keychain.")
                    },
                )
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    phase = FindMyNetworkPhase.CHOOSE_RECOVERY_DEVICE,
                    selectedRecoverySerial = serial,
                    errorMessage = safeMessage(error, "Could not unlock the Apple keychain."),
                )
            }
        }
    }

    fun toggleCandidate(beaconId: String) {
        val selected = _uiState.value.selectedBeaconIds.toMutableSet()
        if (!selected.add(beaconId)) selected.remove(beaconId)
        _uiState.value = _uiState.value.copy(selectedBeaconIds = selected)
    }

    fun importSelectedAccessories() {
        val selected = _uiState.value.selectedBeaconIds
        runOperation(
            phase = FindMyNetworkPhase.IMPORTING_ACCESSORIES,
            fallback = FindMyNetworkPhase.CHOOSE_ACCESSORIES,
        ) {
            client.importRecovered(selected)
            _uiState.value = _uiState.value.copy(
                phase = FindMyNetworkPhase.REFRESHING_REPORTS,
                candidates = emptyList(),
                selectedBeaconIds = emptySet(),
                recoveryDevices = emptyList(),
                message = "Recovered ${selected.size} Find My network accessor${if (selected.size == 1) "y" else "ies"}.",
            )
            refreshReportsInternal()
        }
    }

    fun importOpenTagViewerExport(uri: Uri) {
        pendingExportUri = uri
        importOpenTagViewerExport(uri, null)
    }

    fun submitExportPasscode(passcode: String) {
        val uri = pendingExportUri ?: return
        importOpenTagViewerExport(uri, passcode)
    }

    fun cancelExportPasscode() {
        pendingExportUri = null
        _uiState.value = _uiState.value.copy(
            phase = readyPhase(),
            errorMessage = null,
        )
    }

    private fun importOpenTagViewerExport(uri: Uri, passcode: String?) {
        if (_uiState.value.phase in BUSY_PHASES) return
        _uiState.value = _uiState.value.copy(
            phase = FindMyNetworkPhase.IMPORTING_EXPORT,
            errorMessage = null,
        )
        viewModelScope.launch {
            try {
                when (val result = client.importOpenTagViewerExport(uri, passcode)) {
                    FindMyNetworkExportImportResult.NeedsPasscode -> {
                        pendingExportUri = uri
                        _uiState.value = _uiState.value.copy(
                            phase = FindMyNetworkPhase.ENTER_EXPORT_PASSCODE,
                            errorMessage = null,
                        )
                    }
                    is FindMyNetworkExportImportResult.Imported -> {
                        pendingExportUri = null
                        _uiState.value = _uiState.value.copy(
                            phase = FindMyNetworkPhase.REFRESHING_REPORTS,
                            recoveryDevices = emptyList(),
                            selectedRecoverySerial = null,
                            accessories = result.accessories,
                            message = "Imported ${result.count} accessor${if (result.count == 1) "y" else "ies"} from the OpenTagViewer export.",
                            errorMessage = null,
                        )
                        refreshReportsInternal()
                    }
                }
            } catch (error: FindMyNetworkException) {
                val retryPasscode = error.reason == FindMyNetworkReasons.EXPORT_WRONG_PASSCODE ||
                    error.reason == "export_invalid_passcode_format"
                if (!retryPasscode) pendingExportUri = null
                _uiState.value = _uiState.value.copy(
                    phase = if (retryPasscode) {
                        FindMyNetworkPhase.ENTER_EXPORT_PASSCODE
                    } else {
                        readyPhase()
                    },
                    errorMessage = safeMessage(error, "The OpenTagViewer export could not be imported."),
                )
            } catch (error: Exception) {
                pendingExportUri = null
                _uiState.value = _uiState.value.copy(
                    phase = readyPhase(),
                    errorMessage = safeMessage(error, "The OpenTagViewer export could not be imported."),
                )
            }
        }
    }

    fun refreshReports() {
        if (_uiState.value.phase == FindMyNetworkPhase.REFRESHING_REPORTS ||
            _uiState.value.refreshingBeaconIds.isNotEmpty()
        ) return
        runOperation(
            phase = FindMyNetworkPhase.REFRESHING_REPORTS,
            fallback = readyPhase(),
        ) {
            refreshReportsInternal()
        }
    }

    /** Refresh only one accessory's report window and merge it into the cached library. */
    fun refreshAccessory(beaconId: String) {
        if (beaconId.isBlank() || _uiState.value.refreshingBeaconIds.isNotEmpty() ||
            _uiState.value.phase in BUSY_PHASES
        ) return
        _uiState.value = _uiState.value.copy(
            refreshingBeaconIds = setOf(beaconId),
            errorMessage = null,
            message = null,
        )
        viewModelScope.launch {
            try {
                val refreshed = client.fetchReports(
                    hoursBack = DEVICE_REFRESH_HOURS,
                    beaconIds = setOf(beaconId),
                ).singleOrNull()
                    ?: throw FindMyNetworkException("That accessory is no longer available.")
                val merged = _uiState.value.accessories.map { existing ->
                    if (existing.beaconId != beaconId) {
                        existing
                    } else {
                        refreshed.copy(
                            // A one-hour refresh with no new report must not erase the last known
                            // location already displayed from the wider history window.
                            location = newestLocation(existing.location, refreshed.location),
                            // reportCount describes the normal wide history window; a one-hour
                            // row refresh must not silently redefine that field.
                            reportCount = existing.reportCount,
                        )
                    }
                }
                _uiState.value = _uiState.value.copy(
                    accessories = merged,
                    lastUpdatedMillis = System.currentTimeMillis(),
                    refreshingBeaconIds = emptySet(),
                    errorMessage = null,
                )
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    refreshingBeaconIds = emptySet(),
                    errorMessage = safeMessage(error, "Could not refresh this accessory."),
                )
            }
        }
    }

    fun cancelRecovery() {
        viewModelScope.launch {
            client.closeRecovery()
            _uiState.value = _uiState.value.copy(
                phase = readyPhase(),
                recoveryDevices = emptyList(),
                selectedRecoverySerial = null,
                candidates = emptyList(),
                selectedBeaconIds = emptySet(),
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            client.signOut()
            _uiState.value = FindMyNetworkUiState(
                phase = FindMyNetworkPhase.SIGNED_OUT,
                appleId = appleIdHint,
                anisetteState = _uiState.value.anisetteState,
                anisetteDetail = _uiState.value.anisetteDetail,
            )
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun signedInReadyToRecover(appleId: String) {
        _uiState.value = _uiState.value.copy(
            phase = FindMyNetworkPhase.READY_TO_RECOVER,
            appleId = appleId.trim(),
            twoFactorMethods = emptyList(),
            selectedTwoFactorMethod = null,
            errorMessage = null,
        )
    }

    private suspend fun refreshReportsInternal() {
        val reports = client.fetchReports()
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "Find My network reports: " + reports.joinToString { accessory ->
                    "${accessory.label}(${accessory.beaconId.takeLast(8)}):${accessory.location?.timestampMillis ?: "none"}"
                },
            )
        }
        _uiState.value = _uiState.value.copy(
            phase = FindMyNetworkPhase.READY,
            accessories = reports,
            lastUpdatedMillis = System.currentTimeMillis(),
            errorMessage = null,
        )
    }

    private fun readyPhase(): FindMyNetworkPhase =
        if (_uiState.value.accessories.isEmpty()) {
            FindMyNetworkPhase.READY_TO_RECOVER
        } else {
            FindMyNetworkPhase.READY
        }

    private fun runOperation(
        phase: FindMyNetworkPhase,
        fallback: FindMyNetworkPhase,
        operation: suspend () -> Unit,
    ) {
        if (_uiState.value.phase in BUSY_PHASES) return
        _uiState.value = _uiState.value.copy(phase = phase, errorMessage = null)
        viewModelScope.launch {
            try {
                operation()
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    phase = fallback,
                    errorMessage = safeMessage(error, "Find My network failed."),
                )
            }
        }
    }

    override fun onCleared() {
        cleanupScope.launch {
            try {
                client.closeRecovery()
            } finally {
                cleanupScope.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "FindMyTrace"
        const val DEVICE_REFRESH_HOURS = 1

        val BUSY_PHASES = setOf(
            FindMyNetworkPhase.SIGNING_IN,
            FindMyNetworkPhase.OPENING_RECOVERY,
            FindMyNetworkPhase.UNLOCKING_KEYCHAIN,
            FindMyNetworkPhase.IMPORTING_ACCESSORIES,
            FindMyNetworkPhase.IMPORTING_EXPORT,
            FindMyNetworkPhase.REFRESHING_REPORTS,
        )

        fun safeMessage(error: Exception, fallback: String): String =
            error.message?.trim()?.takeIf(String::isNotBlank)?.take(600) ?: fallback
    }
}

internal fun newestLocation(
    first: FindMyLocation?,
    second: FindMyLocation?,
): FindMyLocation? = listOfNotNull(first, second).maxByOrNull {
    it.timestampMillis ?: Long.MIN_VALUE
}
