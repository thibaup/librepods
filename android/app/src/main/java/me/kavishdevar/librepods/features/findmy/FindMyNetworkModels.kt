package me.kavishdevar.librepods.features.findmy

enum class FindMyNetworkPhase {
    RESTORING,
    SIGNED_OUT,
    SIGNING_IN,
    CHOOSE_TWO_FACTOR_METHOD,
    ENTER_TWO_FACTOR_CODE,
    READY_TO_RECOVER,
    OPENING_RECOVERY,
    CHOOSE_RECOVERY_DEVICE,
    UNLOCKING_KEYCHAIN,
    CHOOSE_ACCESSORIES,
    IMPORTING_ACCESSORIES,
    REFRESHING_REPORTS,
    READY,
    ERROR,
}

enum class FindMyNetworkAnisetteState {
    NOT_CHECKED,
    CHECKING,
    LOCAL_READY,
    REMOTE_FALLBACK,
}

data class FindMyNetworkTwoFactorMethod(
    val index: Int,
    val label: String,
)

data class FindMyRecoveryDevice(
    val serial: String,
    val description: String,
)

data class FindMyRecoveryCandidate(
    val beaconId: String,
    val label: String,
    val details: String,
    val hasName: Boolean,
)

data class FindMyNetworkAccessory(
    val beaconId: String,
    val label: String,
    val location: FindMyLocation?,
    val reportCount: Int,
    /** Optional descriptive metadata from the recovered OwnedBeacons record. */
    val hardwareLabel: String? = null,
    /** Optional serial-like identifier carried by an imported accessory record. */
    val serialNumber: String? = null,
)

data class FindMyNetworkUiState(
    val phase: FindMyNetworkPhase = FindMyNetworkPhase.RESTORING,
    val appleId: String = "",
    val anisetteState: FindMyNetworkAnisetteState = FindMyNetworkAnisetteState.NOT_CHECKED,
    val anisetteDetail: String? = null,
    val twoFactorMethods: List<FindMyNetworkTwoFactorMethod> = emptyList(),
    val selectedTwoFactorMethod: Int? = null,
    val recoveryDevices: List<FindMyRecoveryDevice> = emptyList(),
    val selectedRecoverySerial: String? = null,
    val passcodeAttempts: Int = 0,
    val candidates: List<FindMyRecoveryCandidate> = emptyList(),
    val selectedBeaconIds: Set<String> = emptySet(),
    val accessories: List<FindMyNetworkAccessory> = emptyList(),
    val lastUpdatedMillis: Long? = null,
    /** Beacon ids currently being refreshed without blocking the rest of the library. */
    val refreshingBeaconIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
    val message: String? = null,
)

internal object FindMyNetworkReasons {
    const val PASSCODE_REJECTED = "passcode_rejected"
    const val NO_KEYCHAIN_KEYS = "no_keychain_keys"
    const val NO_SUCH_RECORD = "no_such_record"
    const val SESSION_EXPIRED = "session_expired"
}

internal const val FIND_MY_MAX_PASSCODE_ATTEMPTS = 3

internal data class FindMyRecoveryFailureDecision(
    val nextAttempts: Int,
    val stopSession: Boolean,
    val clearDeviceSelection: Boolean,
    val stoppedAfterLimit: Boolean,
)

/**
 * Only an actual escrow rejection spends a passcode attempt. CloudKit/network/decryption
 * failures happen after or around that exchange and must never be presented as a bad PIN.
 */
internal fun decideRecoveryFailure(
    reason: String?,
    currentAttempts: Int,
): FindMyRecoveryFailureDecision {
    if (reason != FindMyNetworkReasons.PASSCODE_REJECTED) {
        return FindMyRecoveryFailureDecision(
            nextAttempts = currentAttempts,
            stopSession = false,
            clearDeviceSelection = reason == FindMyNetworkReasons.NO_KEYCHAIN_KEYS ||
                reason == FindMyNetworkReasons.NO_SUCH_RECORD,
            stoppedAfterLimit = false,
        )
    }

    val nextAttempts = (currentAttempts + 1).coerceAtMost(FIND_MY_MAX_PASSCODE_ATTEMPTS)
    val stopped = nextAttempts >= FIND_MY_MAX_PASSCODE_ATTEMPTS
    return FindMyRecoveryFailureDecision(
        nextAttempts = nextAttempts,
        stopSession = stopped,
        clearDeviceSelection = stopped,
        stoppedAfterLimit = stopped,
    )
}
