package me.kavishdevar.librepods.features.findmy

import android.content.Context
import android.util.Log
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.TimeZone
import java.util.UUID
import me.kavishdevar.librepods.BuildConfig

/**
 * Minimal icloud.com client for the Apple-hosted Find My web service.
 *
 * It implements Apple's SRP sign-in, HSA2 verification, trusted-session
 * restoration and FMIP refresh requests. It does not log request
 * bodies because those bodies contain credentials and session material.
 */
internal class AppleFindMyClient(context: Context) {
    private val sessionStore = SecureFindMySessionStore(context.applicationContext)
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private var session = sessionStore.load() ?: emptySession()
    private var serverContext: JSONObject? = null
    private val cookieMetadata = mutableListOf<CookieMetadata>()

    init {
        session.cookies.mapTo(cookieMetadata) { CookieMetadata.fromStored(it) }
        restoreCookies(session.cookies)
    }

    val appleId: String
        get() = session.appleId

    fun restore(): FindMyAuthResult {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Apple client restore: sessionToken=${!session.sessionToken.isNullOrBlank()}")
        }
        if (session.sessionToken.isNullOrBlank()) return FindMyAuthResult.SignedOut
        return try {
            val account = requestJson(
                method = "POST",
                url = "$SETUP_ENDPOINT/validate",
                body = "null",
            )
            updateAccount(account)
            finishRestore(account)
        } catch (error: FindMyApiException) {
            if (error.statusCode in setOf(401, 421, 450)) {
                // Validation can reject an otherwise renewable browser session.
                // Re-establish it from the encrypted account/trust tokens before
                // asking for credentials, and never erase those tokens merely
                // because an automatic restore failed.
                try {
                    finishRestore(authenticateWithToken())
                } catch (retryError: FindMyApiException) {
                    if (retryError.statusCode in setOf(401, 421, 450)) {
                        FindMyAuthResult.SignedOut
                    } else {
                        throw retryError
                    }
                }
            } else {
                throw error
            }
        }
    }

    private fun finishRestore(account: JSONObject): FindMyAuthResult {
        if (!requiresTwoFactor(account)) {
            return FindMyAuthResult.Ready(refreshDevices(waitForFreshLocation = true))
        }
        val trustedAccount = session.trustToken
            ?.takeIf(String::isNotBlank)
            ?.let { authenticateWithToken() }
        return if (trustedAccount != null && !requiresTwoFactor(trustedAccount)) {
            FindMyAuthResult.Ready(refreshDevices(waitForFreshLocation = true))
        } else {
            FindMyAuthResult.NeedsTwoFactor
        }
    }

    fun signIn(appleId: String, password: String): FindMyAuthResult {
        require(appleId.isNotBlank()) { "Enter your Apple Account email." }
        require(password.isNotEmpty()) { "Enter your Apple Account password." }

        val normalizedAppleId = appleId.trim()
        val reusableTrustedSession = session.takeIf {
            it.appleId.equals(normalizedAppleId, ignoreCase = true)
        }
        cookieManager.cookieStore.removeAll()
        cookieMetadata.clear()
        // serverContext is scoped to the previous FMIP session/account.
        serverContext = null
        session = emptySession(normalizedAppleId).let { fresh ->
            if (reusableTrustedSession == null) {
                fresh
            } else {
                // A manual retry for the same account is still the same trusted browser.
                // Preserve only durable browser identity/trust; discard handshake/session data.
                fresh.copy(
                    clientId = reusableTrustedSession.clientId,
                    accountCountry = reusableTrustedSession.accountCountry,
                    trustToken = reusableTrustedSession.trustToken,
                    deviceSerials = reusableTrustedSession.deviceSerials,
                )
            }
        }
        val srp = AppleSrpClient(session.appleId, password)
        try {
            val start = srp.start()
            val initBody = JSONObject()
                .put("a", start.publicA.base64())
                .put("accountName", session.appleId)
                .put("protocols", JSONArray().put("s2k").put("s2k_fo"))
            val init = requestJson(
                method = "POST",
                url = "$AUTH_ENDPOINT/signin/init",
                body = initBody.toString(),
                headers = authHeaders(),
            )

            val salt = Base64.decode(init.getString("salt"), Base64.DEFAULT)
            val publicB = Base64.decode(init.getString("b"), Base64.DEFAULT)
            val proof = srp.processChallenge(
                salt = salt,
                iterations = init.getInt("iteration"),
                publicBBytes = publicB,
            )
            salt.fill(0)
            publicB.fill(0)

            val completeBody = JSONObject()
                .put("accountName", session.appleId)
                .put("c", init.getString("c"))
                .put("m1", proof.m1.base64())
                .put("m2", proof.m2.base64())
                .put("rememberMe", true)
                .put("trustTokens", JSONArray().apply {
                    session.trustToken?.takeIf(String::isNotBlank)?.let(::put)
                })
            val complete = request(
                method = "POST",
                url = "$AUTH_ENDPOINT/signin/complete?isRememberMeEnabled=true",
                body = completeBody.toString(),
                headers = authHeaders(),
            )
            // Apple's HSA2 handoff uses HTTP 409 while returning the web session
            // token in response headers. Only accept that status when the token was
            // actually captured; other 409 responses are genuine auth failures.
            if (!complete.isAcceptedAuthConflict()) {
                complete.requireSuccess("Apple Account sign-in failed")
            }
            // The session token is delivered in response headers for both the normal success and
            // Apple's accepted HSA2 conflict response. Checkpoint it before the next request so a
            // network failure cannot discard a successful sign-in step.
            persistSession()
        } finally {
            srp.clear()
        }

        val account = authenticateWithToken()
        persistSession()
        return if (requiresTwoFactor(account)) {
            // Failure to trigger a fresh push is non-fatal: Apple may already have
            // delivered a code, and the user can explicitly request another one.
            triggerTwoFactor(failureIsFatal = false)
            FindMyAuthResult.NeedsTwoFactor
        } else {
            FindMyAuthResult.Ready(refreshDevices(waitForFreshLocation = true))
        }
    }

    fun submitTwoFactor(code: String): List<FindMyDevice> {
        require(code.length == 6 && code.all(Char::isDigit)) {
            "Enter the six-digit verification code."
        }
        val headers = authHeaders()
        val verification = request(
            method = "POST",
            url = "$AUTH_ENDPOINT/verify/trusteddevice/securitycode",
            body = JSONObject()
                .put("securityCode", JSONObject().put("code", code))
                .toString(),
            headers = headers,
        )
        if (!verification.isAcceptedAuthConflict()) {
            verification.requireSuccess("The verification code was rejected")
        }
        request(
            method = "GET",
            url = "$AUTH_ENDPOINT/2sv/trust",
            headers = authHeaders(),
        ).requireSuccess("Apple could not trust this session")
        // /2sv/trust may rotate the trust token and cookies. Persist before accountLogin.
        persistSession()

        val account = authenticateWithToken()
        if (requiresTwoFactor(account)) {
            throw FindMyApiException("Apple still requires verification. Request a new code and try again.")
        }
        persistSession()
        return refreshDevices(waitForFreshLocation = true)
    }

    /**
     * Refresh the account. Explicit user refreshes get one bounded follow-up request because
     * Apple's first `refreshClient` response can acknowledge locating before the new position is
     * present in the response. The snapshots are merged by the newest parsed location so a
     * follow-up that contains no location cannot erase the last known one.
     */
    fun refreshDevices(
        waitForFreshLocation: Boolean = false,
        targetDeviceId: String? = null,
    ): List<FindMyDevice> = withSessionRenewal {
        val first = refreshDevicesOnce(targetDeviceId)
        if (!waitForFreshLocation) return@withSessionRenewal first

        // Keep the delay short enough for the UI while allowing Apple's locating request to settle.
        Thread.sleep(LOCATION_REFRESH_FOLLOW_UP_DELAY_MILLIS)
        mergeDeviceSnapshots(first, refreshDevicesOnce(targetDeviceId))
    }

    private fun mergeDeviceSnapshots(
        first: List<FindMyDevice>,
        second: List<FindMyDevice>,
    ): List<FindMyDevice> {
        val candidates = first + second
        if (candidates.isEmpty()) return second
        return candidates
            .groupBy { it.id }
            .values
            .mapNotNull { sameId ->
                sameId.maxWithOrNull(
                    compareBy<FindMyDevice> { it.location != null }
                        .thenBy { it.location?.timestampMillis != null }
                        .thenBy { it.location?.timestampMillis ?: Long.MIN_VALUE }
                        .thenBy { it.name.lowercase() },
                )
            }
            .sortedWith(
                compareByDescending<FindMyDevice> { it.isAccessory }
                    .thenByDescending { it.location?.timestampMillis ?: Long.MIN_VALUE }
                    .thenBy { it.name.lowercase() },
            )
    }

    private fun refreshDevicesOnce(selectedDeviceId: String? = null): List<FindMyDevice> {
        requireFindMySession()
        val requestBody = JSONObject()
            .put(
                "clientContext",
                findMyClientContext(shouldLocate = true, selectedDeviceId = selectedDeviceId),
            )
        serverContext?.withServerContextId()?.let { requestBody.put("serverContext", it) }
        val response = requestJson(
            method = "POST",
            url = findMyUrl("refreshClient"),
            body = requestBody.toString(),
            headers = mapOf("Content-Type" to "text/plain;charset=UTF-8"),
        )
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Apple refreshClient response content=${response.optJSONArray("content")?.length() ?: 0}")
        }
        serverContext = response.optJSONObject("serverContext")
            ?.let { JSONObject(it.toString()) }
        val content = response.optJSONArray("content") ?: JSONArray()
        val devices = buildList {
            for (index in 0 until content.length()) {
                add(content.getJSONObject(index).toFindMyDevice())
            }
        }.map { device ->
            val cachedSerial = session.deviceSerials[device.id]
            if (device.serialNumber.isNullOrBlank() && !cachedSerial.isNullOrBlank()) {
                device.copy(serialNumber = cachedSerial)
            } else {
                device
            }
        }.also { parsed ->
            val learnedSerials = session.deviceSerials.toMutableMap()
            parsed.forEach { device ->
                device.serialNumber?.trim()?.takeIf(String::isNotBlank)?.let { serial ->
                    learnedSerials[device.id] = serial
                }
            }
            session = session.copy(deviceSerials = learnedSerials)
        }.sortedWith(
            compareByDescending<FindMyDevice> { it.isAccessory }
                .thenByDescending { it.location?.timestampMillis ?: Long.MIN_VALUE }
                .thenBy { it.name.lowercase() },
        )
        persistSession()
        return devices
    }

    private inline fun <T> withSessionRenewal(operation: () -> T): T {
        return try {
            operation()
        } catch (error: FindMyApiException) {
            if (error.statusCode !in RENEWABLE_SESSION_STATUSES) throw error
            serverContext = null
            val account = authenticateWithToken()
            if (requiresTwoFactor(account)) {
                throw FindMyApiException(
                    "Apple requires verification before this Find My session can continue.",
                    statusCode = error.statusCode,
                )
            }
            operation()
        }
    }

    fun requestNewTwoFactorCode() {
        triggerTwoFactor(failureIsFatal = true)
    }

    fun signOut() {
        cookieManager.cookieStore.removeAll()
        cookieMetadata.clear()
        serverContext = null
        sessionStore.clear()
        session = emptySession()
    }

    private fun triggerTwoFactor(failureIsFatal: Boolean) {
        // Required by Apple's iOS 26-era web flow before prompting for a code.
        try {
            request(
                method = "PUT",
                url = "$AUTH_ENDPOINT/verify/trusteddevice/securitycode",
                headers = authHeaders(accept = "application/json"),
            ).requireSuccess("Apple could not send a verification code")
            persistSession()
        } catch (error: FindMyApiException) {
            if (failureIsFatal) throw error
        }
    }

    private fun authenticateWithToken(): JSONObject {
        val sessionToken = session.sessionToken
            ?: throw FindMyApiException("Apple did not return a web session token.")
        val body = JSONObject()
            .put("accountCountryCode", session.accountCountry ?: JSONObject.NULL)
            .put("dsWebAuthToken", sessionToken)
            .put("extended_login", true)
            .put("trustToken", session.trustToken.orEmpty())
        val account = requestJson(
            method = "POST",
            url = "$SETUP_ENDPOINT/accountLogin",
            body = body.toString(),
        )
        updateAccount(account)
        return account
    }

    private fun updateAccount(account: JSONObject) {
        val previousFindMeUrl = session.findMeUrl
        val previousDsid = session.dsid
        val findMeUrl = account.optJSONObject("webservices")
            ?.optJSONObject("findme")
            ?.optString("url")
            ?.takeIf(String::isNotBlank)
        val dsid = account.optJSONObject("dsInfo")
            ?.optString("dsid")
            ?.takeIf(String::isNotBlank)
        session = session.copy(
            findMeUrl = findMeUrl ?: session.findMeUrl,
            dsid = dsid ?: session.dsid,
        )
        if ((findMeUrl != null && findMeUrl != previousFindMeUrl) ||
            (dsid != null && dsid != previousDsid)
        ) {
            serverContext = null
        }
        persistSession()
    }

    private fun requireFindMySession() {
        if (session.findMeUrl.isNullOrBlank()) {
            throw FindMyApiException(
                "Find My web access is unavailable. Enable 'Access iCloud Data on the Web' " +
                    "for this Apple Account; Advanced Data Protection may need to be disabled.",
            )
        }
        if (session.dsid.isNullOrBlank()) {
            throw FindMyApiException("Apple did not return the account identifier required by Find My.")
        }
    }

    private fun findMyUrl(action: String): String =
        "${session.findMeUrl!!.trimEnd('/')}/fmipservice/client/web/$action" +
            "?clientBuildNumber=$FIND_MY_BUILD" +
            "&clientMasteringNumber=$FIND_MY_BUILD" +
            "&clientId=${session.clientId.urlEncode()}" +
            "&dsid=${session.dsid!!.urlEncode()}"

    private fun findMyClientContext(
        shouldLocate: Boolean = false,
        selectedDeviceId: String? = null,
    ): JSONObject = JSONObject()
        .put("appName", "iCloud Find (Web)")
        .put("appVersion", "2.0")
        .put("apiVersion", "3.0")
        .put("deviceListVersion", 1)
        .put("fmly", true)
        .put("timezone", TimeZone.getDefault().id)
        .put("inactiveTime", 0)
        .apply {
            if (shouldLocate) {
                put("shouldLocate", true)
                put("selectedDevice", selectedDeviceId?.takeIf(String::isNotBlank) ?: "all")
            }
        }

    private fun JSONObject.withServerContextId(): JSONObject =
        JSONObject(toString()).put("id", "server_ctx")

    private fun requiresTwoFactor(account: JSONObject): Boolean {
        val dsInfo = account.optJSONObject("dsInfo") ?: return false
        return dsInfo.optInt("hsaVersion", 0) == 2 &&
            (account.optBoolean("hsaChallengeRequired", false) ||
                !account.optBoolean("hsaTrustedBrowser", false))
    }

    private fun requestJson(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val response = request(method, url, body, headers)
        response.requireSuccess("Apple returned an error")
        return try {
            if (response.body.isBlank()) JSONObject() else JSONObject(response.body)
        } catch (error: Exception) {
            throw FindMyApiException("Apple returned an unreadable response.", error)
        }
    }

    private fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val uri = URI.create(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            if (BuildConfig.DEBUG) Log.d(TAG, "Apple request start $method ${uri.path}")
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Origin", HOME_ENDPOINT)
            connection.setRequestProperty("Referer", "$HOME_ENDPOINT/")
            connection.setRequestProperty("Accept", "application/json, text/javascript")
            if (body != null) connection.setRequestProperty("Content-Type", "application/json")
            headers.forEach(connection::setRequestProperty)
            cookieManager.get(uri, emptyMap()).forEach { (name, values) ->
                connection.setRequestProperty(name, values.joinToString("; "))
            }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            if (BuildConfig.DEBUG) Log.d(TAG, "Apple response ${uri.path} status=$status")
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key!! }
            captureCookieMetadata(uri, responseHeaders)
            cookieManager.put(uri, responseHeaders)
            captureSessionHeaders(responseHeaders)
            val responseBody = (if (status in 200..399) connection.inputStream else connection.errorStream)
                .readSafely()
            return HttpResponse(status, responseBody, responseHeaders)
        } catch (error: FindMyApiException) {
            throw error
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Apple request failed ${uri.path}: ${error.message}")
            throw FindMyApiException("Could not reach Apple's iCloud service.", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun captureSessionHeaders(headers: Map<String, List<String>>) {
        fun value(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.takeIf(String::isNotBlank)

        session = session.copy(
            accountCountry = value("X-Apple-ID-Account-Country") ?: session.accountCountry,
            sessionId = value("X-Apple-ID-Session-Id") ?: session.sessionId,
            sessionToken = value("X-Apple-Session-Token") ?: session.sessionToken,
            trustToken = value("X-Apple-TwoSV-Trust-Token") ?: session.trustToken,
            scnt = value("scnt") ?: session.scnt,
        )
    }

    private fun captureCookieMetadata(uri: URI, headers: Map<String, List<String>>) {
        val now = System.currentTimeMillis()
        headers.entries
            .filter { (name, _) ->
                name.equals("Set-Cookie", ignoreCase = true) ||
                    name.equals("Set-Cookie2", ignoreCase = true)
            }
            .flatMap { (_, values) -> values }
            .forEach { header ->
                val parsed = runCatching { HttpCookie.parse(header) }.getOrElse { emptyList() }
                parsed.forEach { cookie ->
                    val hostOnly = cookie.domain.isNullOrBlank()
                    val metadata = CookieMetadata(
                        name = cookie.name,
                        value = cookie.value,
                        normalizedDomain = (cookie.domain ?: uri.host).lowercase(),
                        path = cookie.path ?: defaultCookiePath(uri.path),
                        originUrl = if (hostOnly) uri.cookieOrigin() else null,
                        hostOnly = hostOnly,
                        version = cookie.version,
                        expiresAtMillis = cookie.maxAge
                            .takeIf { it >= 0 }
                            ?.let { now + it * 1_000L },
                    )
                    cookieMetadata.removeAll { it.sameCookieAs(metadata) }
                    cookieMetadata.add(metadata)
                }
            }
    }

    private fun authHeaders(accept: String = "application/json, text/javascript"): Map<String, String> =
        buildMap {
            put("Accept", accept)
            put("Content-Type", "application/json")
            put("X-Apple-OAuth-Client-Id", WIDGET_KEY)
            put("X-Apple-OAuth-Client-Type", "firstPartyAuth")
            put("X-Apple-OAuth-Redirect-URI", HOME_ENDPOINT)
            put("X-Apple-OAuth-Require-Grant-Code", "true")
            put("X-Apple-OAuth-Response-Mode", "web_message")
            put("X-Apple-OAuth-Response-Type", "code")
            put("X-Apple-OAuth-State", session.clientId)
            put("X-Apple-Widget-Key", WIDGET_KEY)
            session.sessionId?.let { put("X-Apple-ID-Session-Id", it) }
            session.scnt?.let { put("scnt", it) }
        }

    private fun persistSession() {
        val now = System.currentTimeMillis()
        val cookies = buildList {
            val seen = mutableSetOf<List<Any?>>()
            cookieManager.cookieStore.urIs.forEach { uri ->
                cookieManager.cookieStore.get(uri)
                    .filterNot(HttpCookie::hasExpired)
                    .forEach { cookie ->
                        val stored = cookie.toStoredCookie(uri, now)
                        val key = listOf(
                            stored.name,
                            stored.value,
                            stored.domain,
                            stored.path,
                            stored.originUrl,
                            stored.hostOnly,
                        )
                        if (seen.add(key)) {
                            add(stored)
                        }
                    }
            }
        }
        session = session.copy(cookies = cookies)
        if (session.sessionToken != null) sessionStore.save(session)
    }

    private fun restoreCookies(cookies: List<StoredFindMyCookie>) {
        cookies.forEach { stored ->
            val uri = if (stored.hostOnly) {
                stored.originUrl?.let { runCatching { URI.create(it) }.getOrNull() }
            } else {
                stored.domain
                    ?.trimStart('.')
                    ?.takeIf(String::isNotBlank)
                    ?.let { host -> runCatching { URI("https", host, "/", null) }.getOrNull() }
            } ?: return@forEach
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) return@forEach
            val cookie = stored.toHttpCookie()
            if (!cookie.hasExpired()) cookieManager.cookieStore.add(uri, cookie)
        }
    }

    private fun HttpCookie.toStoredCookie(uri: URI, now: Long): StoredFindMyCookie {
        val normalizedDomain = (domain ?: uri.host).lowercase()
        val normalizedPath = path ?: defaultCookiePath(uri.path)
        val metadata = cookieMetadata.lastOrNull {
            it.name.equals(name, ignoreCase = true) &&
                it.value == value &&
                it.normalizedDomain.equals(normalizedDomain, ignoreCase = true) &&
                it.path == normalizedPath &&
                (!it.hostOnly || it.originUrl == uri.cookieOrigin())
        }
        val hostOnly = metadata?.hostOnly ?: false
        val originUrl = if (hostOnly) metadata.originUrl ?: uri.cookieOrigin() else null
        val previousExpiry = session.cookies.firstOrNull {
            it.name.equals(name, ignoreCase = true) && it.value == value &&
                it.domain.equals(if (hostOnly) null else domain, ignoreCase = true) &&
                it.path == normalizedPath && it.originUrl == originUrl &&
                it.hostOnly == hostOnly
        }?.expiresAtMillis
        return StoredFindMyCookie(
            name = name,
            value = value,
            originUrl = originUrl,
            hostOnly = hostOnly,
            domain = if (hostOnly) null else domain,
            path = normalizedPath,
            secure = secure,
            version = metadata?.version ?: version,
            expiresAtMillis = metadata?.expiresAtMillis ?: previousExpiry ?: maxAge
                .takeIf { it >= 0 }
                ?.let { now + it * 1_000L },
        )
    }

    private fun JSONObject.toFindMyDevice(): FindMyDevice {
        val locationJson = optJSONObject("location")
        val location = locationJson?.let {
            val latitude = it.optNullableDouble("latitude")
            val longitude = it.optNullableDouble("longitude")
            if (latitude == null || longitude == null) null else FindMyLocation(
                latitude = latitude,
                longitude = longitude,
                horizontalAccuracyMeters = it.optNullableDouble("horizontalAccuracy"),
                timestampMillis = normalizeFindMyTimestampMillis(
                    it.optNullableLong("timeStamp") ?: it.optNullableLong("timestamp"),
                ),
                isOld = it.optBoolean("isOld", false),
                positionType = it.optNullableString("positionType"),
            )
        }
        return FindMyDevice(
            id = optString("id"),
            name = optString("name").ifBlank { optString("deviceDisplayName", "Apple device") },
            displayName = optString("deviceDisplayName", "Apple device"),
            modelName = optNullableString("modelDisplayName"),
            rawModel = optNullableString("rawDeviceModel") ?: optNullableString("deviceModel"),
            batteryLevel = optNullableDouble("batteryLevel"),
            batteryStatus = optNullableString("batteryStatus"),
            deviceStatus = optNullableString("deviceStatus"),
            isAccessory = optBoolean("isConsideredAccessory", false) ||
                optString("deviceClass").contains("Accessory", ignoreCase = true) ||
                optString("deviceClass").contains("AirPods", ignoreCase = true),
            location = location,
            serialNumber = sequenceOf("serialNumber", "serial", "deviceSerialNumber")
                .mapNotNull { key -> optNullableString(key) }
                .firstOrNull(),
        )
    }

    private fun HttpResponse.requireSuccess(prefix: String) {
        if (status in 200..299) return
        val detail = runCatching {
            val json = JSONObject(body)
            sequenceOf("errorMessage", "reason", "errorReason", "error")
                .mapNotNull { key -> json.optString(key).takeIf(String::isNotBlank) }
                .firstOrNull()
        }.getOrNull()
        val friendly = when {
            status == 401 -> "Apple rejected the credentials or session."
            status == 409 -> "Apple requires another authentication step."
            status == 421 || status == 450 -> "The Apple session expired. Sign in again."
            status == 429 -> "Apple is rate-limiting requests. Wait before trying again."
            else -> detail ?: "HTTP $status"
        }
        throw FindMyApiException("$prefix: $friendly", statusCode = status)
    }

    private fun HttpResponse.isAcceptedAuthConflict(): Boolean =
        status == 409 && headers.entries.any { (name, values) ->
            name.equals("X-Apple-Session-Token", ignoreCase = true) &&
                values.any(String::isNotBlank)
        }

    private data class HttpResponse(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>>,
    )

    private data class CookieMetadata(
        val name: String,
        val value: String,
        val normalizedDomain: String,
        val path: String,
        val originUrl: String?,
        val hostOnly: Boolean,
        val version: Int,
        val expiresAtMillis: Long?,
    ) {
        fun sameCookieAs(other: CookieMetadata): Boolean =
            name.equals(other.name, ignoreCase = true) &&
                normalizedDomain.equals(other.normalizedDomain, ignoreCase = true) &&
                path == other.path && originUrl == other.originUrl && hostOnly == other.hostOnly

        companion object {
            fun fromStored(stored: StoredFindMyCookie): CookieMetadata {
                val originHost = stored.originUrl
                    ?.let { runCatching { URI.create(it).host }.getOrNull() }
                    .orEmpty()
                return CookieMetadata(
                    name = stored.name,
                    value = stored.value,
                    normalizedDomain = (stored.domain ?: originHost).lowercase(),
                    path = stored.path ?: "/",
                    originUrl = stored.originUrl,
                    hostOnly = stored.hostOnly,
                    version = stored.version,
                    expiresAtMillis = stored.expiresAtMillis,
                )
            }
        }
    }

    private companion object {
        const val TAG = "FindMyTrace"
        const val AUTH_ENDPOINT = "https://idmsa.apple.com/appleauth/auth"
        const val HOME_ENDPOINT = "https://www.icloud.com"
        const val SETUP_ENDPOINT = "https://setup.icloud.com/setup/ws/1"
        const val WIDGET_KEY = "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"
        const val FIND_MY_BUILD = "2602Build22"
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        const val READ_TIMEOUT_MILLIS = 35_000
        const val LOCATION_REFRESH_FOLLOW_UP_DELAY_MILLIS = 750L
        val RENEWABLE_SESSION_STATUSES = setOf(401, 421, 450)

        fun emptySession(appleId: String = "") = AppleWebSession(
            appleId = appleId,
            clientId = "auth-${UUID.randomUUID().toString().lowercase()}",
            dsid = null,
            accountCountry = null,
            sessionId = null,
            sessionToken = null,
            trustToken = null,
            scnt = null,
            findMeUrl = null,
            cookies = emptyList(),
            deviceSerials = emptyMap(),
        )

        fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

        fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

        fun URI.cookieOrigin(): String = URI(scheme, host, null, null).toString()

        fun defaultCookiePath(uriPath: String?): String {
            val path = uriPath?.takeIf { it.startsWith('/') }.orEmpty()
            val lastSlash = path.lastIndexOf('/')
            return if (lastSlash < 0) "/" else path.substring(0, lastSlash + 1).ifBlank { "/" }
        }

        fun InputStream?.readSafely(): String = this?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        }.orEmpty()

        fun JSONObject.optNullableString(name: String): String? =
            if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

        fun JSONObject.optNullableDouble(name: String): Double? =
            if (!has(name) || isNull(name)) null else optDouble(name).takeUnless(Double::isNaN)

        fun JSONObject.optNullableLong(name: String): Long? =
            if (!has(name) || isNull(name)) null else optLong(name)
    }
}
