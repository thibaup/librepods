package me.kavishdevar.librepods.features.findmy

data class FindMyLocation(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Double?,
    val timestampMillis: Long?,
    val isOld: Boolean,
    val positionType: String?,
)

/**
 * Convert the epoch representation used by a Find My backend to milliseconds.
 *
 * FMIP normally sends milliseconds, while imported/network report implementations have
 * historically exposed seconds. Keeping the conversion at the parser boundary prevents a
 * linked row from comparing unlike units and selecting the wrong source.
 */
internal fun normalizeFindMyTimestampMillis(raw: Long?): Long? {
    val value = raw ?: return null
    if (value <= 0L) return null
    return when {
        // Unix seconds (roughly 1973 through the year 2286).
        value in 100_000_000L until 10_000_000_000L -> value * 1_000L
        // Unix microseconds.
        value >= 100_000_000_000_000L -> value / 1_000L
        else -> value
    }
}

data class FindMyDevice(
    val id: String,
    val name: String,
    val displayName: String,
    val modelName: String?,
    val rawModel: String?,
    val batteryLevel: Double?,
    val batteryStatus: String?,
    val deviceStatus: String?,
    val isAccessory: Boolean,
    val location: FindMyLocation?,
    /** Serial exposed by FMIP when Apple includes it in the device record. */
    val serialNumber: String? = null,
)

enum class FindMyPhase {
    RESTORING,
    SIGNED_OUT,
    SIGNING_IN,
    NEEDS_TWO_FACTOR,
    REFRESHING,
    READY,
    SESSION_ERROR,
    ERROR,
}

data class FindMyUiState(
    val phase: FindMyPhase = FindMyPhase.RESTORING,
    val appleId: String = "",
    val devices: List<FindMyDevice> = emptyList(),
    val lastUpdatedMillis: Long? = null,
    /** Row-level refreshes stay independent from the full-account refresh phase. */
    val refreshingDeviceIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
    val message: String? = null,
)

internal sealed interface FindMyAuthResult {
    data object NeedsTwoFactor : FindMyAuthResult
    data class Ready(val devices: List<FindMyDevice>) : FindMyAuthResult
    data object SignedOut : FindMyAuthResult
}

internal class FindMyApiException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : Exception(message, cause)

internal val FIND_MY_SESSION_EXPIRED_STATUS_CODES = setOf(401, 421, 450)

internal fun FindMyApiException.isSessionExpired(): Boolean =
    statusCode?.let(FIND_MY_SESSION_EXPIRED_STATUS_CODES::contains) == true
