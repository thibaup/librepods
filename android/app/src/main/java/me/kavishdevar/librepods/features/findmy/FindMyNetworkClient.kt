package me.kavishdevar.librepods.features.findmy

import android.content.Context
import com.chaquo.python.Kwarg
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import dev.wander.android.opentagviewer.anisette.LocalAnisette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface FindMyNetworkRestoreResult {
    data object SignedOut : FindMyNetworkRestoreResult
    data class Ready(val hasAccessories: Boolean) : FindMyNetworkRestoreResult
}

internal sealed interface FindMyNetworkLoginResult {
    data class NeedsTwoFactor(
        val methods: List<FindMyNetworkTwoFactorMethod>,
    ) : FindMyNetworkLoginResult

    data object Ready : FindMyNetworkLoginResult
}

internal data class FindMyAnisetteResult(
    val local: Boolean,
    val detail: String?,
)

/** Public JVM getters are consumed reflectively by the Chaquopy Python bridge. */
internal data class FindMyNetworkAccessoryRequest(
    val beaconId: String,
    val accessoryJson: String,
)

internal class FindMyNetworkException(
    message: String,
    val reason: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Find My network accessory records are produced by FindMy.py/OpenTagViewer and their exact
 * schema varies between export/recovery versions. Walk only JSON containers and only accept
 * explicitly named serial fields; never treat an arbitrary key or private key value as a serial.
 */
private fun extractAccessorySerialNumber(accessoryJson: String): String? {
    val root = runCatching { JSONObject(accessoryJson) }.getOrNull() ?: return null
    val serialKeys = setOf("serialNumber", "serial_number", "serial", "deviceSerialNumber")

    fun find(value: Any?, depth: Int): String? {
        if (depth > 6) return null
        return when (value) {
            is JSONObject -> {
                serialKeys.asSequence()
                    .mapNotNull { key ->
                        value.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                    }
                    .firstOrNull()
                    ?: value.keys().asSequence()
                        .mapNotNull { key -> find(value.opt(key), depth + 1) }
                        .firstOrNull()
            }

            is JSONArray -> (0 until value.length())
                .asSequence()
                .mapNotNull { index -> find(value.opt(index), depth + 1) }
                .firstOrNull()

            else -> null
        }
    }

    return find(root, depth = 0)
}

/**
 * Serialized bridge into OpenTagViewer's pinned FindMy.py implementation.
 *
 * One mutex is mandatory: AppleAccount owns an asyncio event loop and Chaquopy must never
 * drive that loop concurrently. The same lock also makes account/key persistence atomic.
 */
internal class FindMyNetworkClient(context: Context) {
    private val appContext = context.applicationContext
    private val store = SecureFindMyNetworkStore(appContext)
    private val localAnisette = LocalAnisette(appContext)
    private val mutex = Mutex()

    private var account: PyObject? = null
    private var appleId: String = ""
    private var accessories: List<StoredFindMyNetworkAccessory> = emptyList()
    private var authMethods: List<PendingAuthMethod> = emptyList()
    private var recoverySession: PyObject? = null
    private var recoveryCandidates: List<FindMyRecoveryCandidate> = emptyList()

    val currentAppleId: String get() = appleId

    suspend fun checkAnisette(): FindMyAnisetteResult = onIo {
        val local = localAnisette.ensureReady()
        FindMyAnisetteResult(local, if (local) null else localAnisette.unavailableReason())
    }

    suspend fun restore(): FindMyNetworkRestoreResult = onIo {
        val stored = store.load()
        if (stored == null) {
            if (store.hasEncryptedState()) {
                throw FindMyNetworkException(
                    "The encrypted Find My network session could not be opened. " +
                        "The stored data was preserved; retry before resetting it.",
                )
            }
            return@onIo FindMyNetworkRestoreResult.SignedOut
        }

        val restored = mainModule().callAttr(
            "getAccount",
            Kwarg("serializedAccountData", stored.accountJson),
            Kwarg("localAnisette", localAnisette),
        ) ?: throw FindMyNetworkException(
            "Apple no longer accepts the saved Find My network session. Sign in again.",
            reason = "session_expired",
        )

        account = restored
        appleId = stored.appleId
        accessories = stored.accessories
        FindMyNetworkRestoreResult.Ready(accessories.isNotEmpty())
    }

    suspend fun login(email: String, password: String): FindMyNetworkLoginResult = onIo {
        closeRecoveryLocked()
        val returned = mainModule().callAttr(
            "loginSync",
            Kwarg("email", email.trim()),
            Kwarg("password", password),
            Kwarg("anisetteServerUrl", DEFAULT_ANISETTE_URL),
            Kwarg("localAnisette", localAnisette),
        ) ?: throw FindMyNetworkException("Apple sign-in returned no result.")

        val result = returned.asMap()
        result.value("error")?.let { error ->
            val reason = result.value("reason")?.toString()
            throw FindMyNetworkException(error.toString(), reason)
        }

        account = result.value("account")
            ?: throw FindMyNetworkException("Apple sign-in did not return an account session.")
        appleId = email.trim()
        val state = result.value("loginState")?.toInt()
            ?: throw FindMyNetworkException("Apple sign-in returned an unknown state.")

        if (state == LOGIN_STATE_REQUIRES_2FA) {
            val rawMethods = result.value("loginMethods")?.asList().orEmpty()
            authMethods = rawMethods.mapIndexed { index, raw ->
                val method = raw.asMap()
                val type = method.value("type")?.toInt() ?: 0
                val phone = method.value("phoneNumber")?.toString()?.takeIf(String::isNotBlank)
                PendingAuthMethod(
                    objectRef = method.value("obj")
                        ?: throw FindMyNetworkException("A verification method was incomplete."),
                    ui = FindMyNetworkTwoFactorMethod(
                        index = index,
                        label = when (type) {
                            TWO_FACTOR_TRUSTED_DEVICE -> "Trusted Apple device"
                            TWO_FACTOR_PHONE -> phone?.let { "Text message to $it" }
                                ?: "Text message"
                            else -> "Apple verification method ${index + 1}"
                        },
                    ),
                )
            }
            if (authMethods.isEmpty()) {
                throw FindMyNetworkException("Apple requires verification but offered no method.")
            }
            return@onIo FindMyNetworkLoginResult.NeedsTwoFactor(authMethods.map { it.ui })
        }

        if (state != LOGIN_STATE_LOGGED_IN) {
            throw FindMyNetworkException("Apple sign-in stopped in state $state.")
        }
        authMethods = emptyList()
        persistAccountLocked()
        FindMyNetworkLoginResult.Ready
    }

    suspend fun requestTwoFactorCode(methodIndex: Int) = onIo {
        val method = authMethods.firstOrNull { it.ui.index == methodIndex }
            ?: throw FindMyNetworkException("That verification method is no longer available.")
        method.objectRef.callAttr("request")
        Unit
    }

    suspend fun submitTwoFactorCode(methodIndex: Int, code: String) = onIo {
        val method = authMethods.firstOrNull { it.ui.index == methodIndex }
            ?: throw FindMyNetworkException("That verification method is no longer available.")
        method.objectRef.callAttr("submit", Kwarg("code", code))
        persistAccountLocked()
        authMethods = emptyList()
        Unit
    }

    suspend fun openRecovery(): List<FindMyRecoveryDevice> = onIo {
        val current = requireAccount()
        closeRecoveryLocked()
        val session = icloudModule().callAttr("openSession", current)
            ?: throw FindMyNetworkException("The saved Apple session cannot open keychain recovery.")
        recoverySession = session

        requireOk(session.callAttr("open"))
        val options = requireOk(session.callAttr("recoveryOptions"))
        val devices = options.optJSONArray("devices") ?: JSONArray()
        buildList {
            for (index in 0 until devices.length()) {
                val item = devices.getJSONObject(index)
                add(
                    FindMyRecoveryDevice(
                        serial = item.getString("serial"),
                        description = item.optString("description").ifBlank {
                            item.getString("serial")
                        },
                    ),
                )
            }
        }
    }

    suspend fun unlockAndFetch(serial: String, passcode: String): List<FindMyRecoveryCandidate> =
        onIo {
            val session = requireRecoverySession()
            requireOk(session.callAttr("unlock", serial, passcode))
            val fetched = requireOk(session.callAttr("fetch"))
            val items = fetched.optJSONArray("accessories") ?: JSONArray()
            recoveryCandidates = buildList {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    add(
                        FindMyRecoveryCandidate(
                            beaconId = item.getString("beaconId"),
                            label = item.optString("label").ifBlank { "Unnamed accessory" },
                            details = item.optString("details"),
                            hasName = item.optBoolean("hasName"),
                        ),
                    )
                }
            }
            recoveryCandidates
        }

    suspend fun importRecovered(selectedBeaconIds: Set<String>) = onIo {
        if (selectedBeaconIds.isEmpty()) {
            throw FindMyNetworkException("Select at least one accessory to import.")
        }
        val session = requireRecoverySession()
        try {
            val selection = JSONArray().apply {
                recoveryCandidates
                    .filter { it.beaconId in selectedBeaconIds }
                    .forEach { put(JSONObject().put("beaconId", it.beaconId)) }
            }
            if (selection.length() != selectedBeaconIds.size) {
                throw FindMyNetworkException("The recovery selection is no longer current.")
            }

            val records = requireOk(session.callAttr("records", selection.toString()))
            val array = records.optJSONArray("accessories") ?: JSONArray()
            val labels = recoveryCandidates.associate { it.beaconId to it.label }
            val imported = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val beaconId = item.getString("beaconId")
                    val alignment = item.optNullableString("keyAlignmentPlist")
                    val converted = if (alignment == null) {
                        mainModule().callAttr(
                            "convertPlistToJson",
                            item.getString("ownedBeaconPlist"),
                        )
                    } else {
                        mainModule().callAttr(
                            "convertPlistToJson",
                            item.getString("ownedBeaconPlist"),
                            alignment,
                        )
                    }?.toString()?.takeIf(String::isNotBlank)
                        ?: throw FindMyNetworkException(
                            "Could not convert recovered accessory ${labels[beaconId] ?: beaconId}.",
                        )
                    add(
                        StoredFindMyNetworkAccessory(
                            beaconId = beaconId,
                            label = labels[beaconId] ?: "Unnamed accessory",
                            accessoryJson = converted,
                            serialNumber = extractAccessorySerialNumber(converted),
                        ),
                    )
                }
            }

            val byId = accessories.associateBy(StoredFindMyNetworkAccessory::beaconId).toMutableMap()
            imported.forEach { byId[it.beaconId] = it }
            accessories = byId.values.sortedBy { it.label.lowercase() }
            persistAccountLocked()
        } finally {
            closeRecoveryLocked()
        }
    }

    suspend fun fetchReports(
        hoursBack: Int = 168,
        beaconIds: Set<String>? = null,
    ): List<FindMyNetworkAccessory> = onIo {
        val current = requireAccount()
        if (accessories.isEmpty()) return@onIo emptyList()

        val selected = if (beaconIds == null) {
            accessories
        } else {
            accessories.filter { it.beaconId in beaconIds }
        }
        if (selected.isEmpty()) return@onIo emptyList()

        val requests = ArrayList<FindMyNetworkAccessoryRequest>(selected.size)
        selected.forEach {
            requests.add(FindMyNetworkAccessoryRequest(it.beaconId, it.accessoryJson))
        }
        val returned = mainModule().callAttr("getLastReports", current, requests, hoursBack)
            ?: throw FindMyNetworkException("Apple returned no Find My network reports.")
        val json = JSONObject(
            Python.getInstance().getModule("json").callAttr("dumps", returned).toString(),
        )

        val updated = accessories.map { stored ->
            val result = json.optJSONObject(stored.beaconId)
            val accessoryJson = result?.optString("updatedAccessoryJson")
                ?.takeIf(String::isNotBlank) ?: stored.accessoryJson
            stored.copy(
                accessoryJson = accessoryJson,
                serialNumber = stored.serialNumber ?: extractAccessorySerialNumber(accessoryJson),
            )
        }
        accessories = updated
        persistAccountLocked()

        val updatedById = updated.associateBy(StoredFindMyNetworkAccessory::beaconId)
        selected.map { selectedStored ->
            val stored = updatedById.getValue(selectedStored.beaconId)
            val reports = json.optJSONObject(stored.beaconId)?.optJSONArray("reports") ?: JSONArray()
            var latest: JSONObject? = null
            for (index in 0 until reports.length()) {
                val report = reports.getJSONObject(index)
                val timestamp = normalizeFindMyTimestampMillis(report.optNullableLong("timestamp"))
                    ?: Long.MIN_VALUE
                val currentTimestamp = normalizeFindMyTimestampMillis(
                    latest?.optNullableLong("timestamp"),
                ) ?: Long.MIN_VALUE
                if (latest == null || timestamp > currentTimestamp ||
                    (timestamp == currentTimestamp &&
                        report.reportTieBreaker() > latest.reportTieBreaker())
                ) {
                    latest = report
                }
            }
                val timestamp = normalizeFindMyTimestampMillis(latest?.optNullableLong("timestamp"))
            FindMyNetworkAccessory(
                beaconId = stored.beaconId,
                label = stored.label,
                location = latest?.let { report ->
                    FindMyLocation(
                        latitude = report.getDouble("latitude"),
                        longitude = report.getDouble("longitude"),
                        horizontalAccuracyMeters = report.optNullableDouble("horizontalAccuracy"),
                        timestampMillis = timestamp,
                        isOld = timestamp == null ||
                            System.currentTimeMillis() - timestamp > LOCATION_FRESH_FOR_MS,
                        positionType = report.optString("status").takeIf(String::isNotBlank),
                    )
                },
                reportCount = reports.length(),
                serialNumber = stored.serialNumber ?: extractAccessorySerialNumber(stored.accessoryJson),
            )
        }
    }

    suspend fun signOut() = onIo {
        closeRecoveryLocked()
        account = null
        authMethods = emptyList()
        recoveryCandidates = emptyList()
        accessories = emptyList()
        appleId = ""
        store.clear()
        Unit
    }

    suspend fun closeRecovery() = onIo {
        closeRecoveryLocked()
        Unit
    }

    private suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun persistAccountLocked() {
        val current = requireAccount()
        val serialized = mainModule().callAttr(
            "exportToString",
            Kwarg("account", current),
        )?.toString()?.takeIf(String::isNotBlank)
            ?: throw FindMyNetworkException("The Apple session could not be encrypted for storage.")
        store.save(
            StoredFindMyNetworkState(
                appleId = appleId,
                accountJson = serialized,
                accessories = accessories,
            ),
        )
    }

    private fun requireAccount(): PyObject = account
        ?: throw FindMyNetworkException("Sign in to the Find My network first.", "not_signed_in")

    private fun requireRecoverySession(): PyObject = recoverySession
        ?: throw FindMyNetworkException("The keychain recovery session has ended. Start again.")

    private fun requireOk(raw: PyObject?): JSONObject {
        val json = try {
            JSONObject(raw?.toString() ?: "")
        } catch (error: Exception) {
            throw FindMyNetworkException("The Apple recovery service returned an invalid response.", cause = error)
        }
        if (!json.optBoolean("ok", false)) {
            throw FindMyNetworkException(
                json.optString("message").ifBlank { "Apple recovery failed." },
                reason = json.optString("reason").takeIf(String::isNotBlank),
            )
        }
        return json
    }

    private fun requireBridgeOk(raw: PyObject?, invalidMessage: String): JSONObject {
        val json = try {
            JSONObject(raw?.toString() ?: "")
        } catch (error: Exception) {
            throw FindMyNetworkException(invalidMessage, cause = error)
        }
        if (!json.optBoolean("ok", false)) {
            throw FindMyNetworkException(json.optString("message").ifBlank { invalidMessage })
        }
        return json
    }

    private fun closeRecoveryLocked() {
        try {
            recoverySession?.callAttr("close")
        } finally {
            recoverySession = null
            recoveryCandidates = emptyList()
        }
    }

    private fun mainModule(): PyObject = Python.getInstance().getModule("main")
    private fun icloudModule(): PyObject = Python.getInstance().getModule("icloud_bridge")

    private data class PendingAuthMethod(
        val objectRef: PyObject,
        val ui: FindMyNetworkTwoFactorMethod,
    )

    private companion object {
        const val DEFAULT_ANISETTE_URL = "https://ani.sidestore.io"
        const val LOGIN_STATE_REQUIRES_2FA = 1
        const val LOGIN_STATE_LOGGED_IN = 3
        const val TWO_FACTOR_TRUSTED_DEVICE = 1
        const val TWO_FACTOR_PHONE = 2
        const val LOCATION_FRESH_FOR_MS = 15 * 60 * 1_000L

        fun Map<PyObject, PyObject>.value(name: String): PyObject? =
            entries.firstOrNull { it.key.toString() == name }?.value

        fun JSONObject.optNullableString(name: String): String? =
            if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

        fun JSONObject.optNullableLong(name: String): Long? =
            if (!has(name) || isNull(name)) null else optLong(name)

        fun JSONObject.optNullableDouble(name: String): Double? =
            if (!has(name) || isNull(name)) null else optDouble(name)

        fun JSONObject.reportTieBreaker(): String = listOf(
            optNullableDouble("latitude")?.toString().orEmpty(),
            optNullableDouble("longitude")?.toString().orEmpty(),
            optNullableDouble("horizontalAccuracy")?.toString().orEmpty(),
            optString("status"),
        ).joinToString("|")
    }
}
