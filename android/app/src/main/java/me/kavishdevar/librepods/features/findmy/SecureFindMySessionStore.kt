package me.kavishdevar.librepods.features.findmy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpCookie
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class StoredFindMyCookie(
    val name: String,
    val value: String,
    val originUrl: String?,
    val hostOnly: Boolean,
    val domain: String?,
    val path: String?,
    val secure: Boolean,
    val version: Int,
    val expiresAtMillis: Long?,
) {
    fun toHttpCookie(nowMillis: Long = System.currentTimeMillis()): HttpCookie =
        HttpCookie(name, value).also { cookie ->
            cookie.domain = if (hostOnly) null else domain
            cookie.path = path
            cookie.secure = secure
            cookie.version = version
            expiresAtMillis?.let {
                cookie.maxAge = ((it - nowMillis) / 1_000L).coerceAtLeast(0L)
            }
        }
}

internal data class AppleWebSession(
    val appleId: String,
    val clientId: String,
    val dsid: String?,
    val accountCountry: String?,
    val sessionId: String?,
    val sessionToken: String?,
    val trustToken: String?,
    val scnt: String?,
    val findMeUrl: String?,
    val cookies: List<StoredFindMyCookie>,
    /** Stable device serials learned from FMIP, keyed by the FMIP device id. */
    val deviceSerials: Map<String, String> = emptyMap(),
)

/** Stores Apple web tokens and cookies under an Android Keystore AES key. */
internal class SecureFindMySessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(session: AppleWebSession) {
        val plaintext = session.toJson().toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        plaintext.fill(0)
        preferences.edit()
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(DATA_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
    }

    fun load(): AppleWebSession? {
        val iv = preferences.getString(IV_KEY, null) ?: return null
        val data = preferences.getString(DATA_KEY, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))
            try {
                decodeSession(JSONObject(plaintext.toString(Charsets.UTF_8)))
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            // Preserve the encrypted blob. A schema/migration or temporary
            // Keystore failure must not silently turn into a destructive logout;
            // a later successful login will safely replace it.
            null
        }
    }

    fun clear() {
        preferences.edit().clear().commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun AppleWebSession.toJson(): JSONObject = JSONObject().apply {
        put("appleId", appleId)
        put("clientId", clientId)
        putNullable("dsid", dsid)
        putNullable("accountCountry", accountCountry)
        putNullable("sessionId", sessionId)
        putNullable("sessionToken", sessionToken)
        putNullable("trustToken", trustToken)
        putNullable("scnt", scnt)
        putNullable("findMeUrl", findMeUrl)
        put("deviceSerials", JSONObject().apply {
            deviceSerials.forEach { (deviceId, serial) -> put(deviceId, serial) }
        })
        put("cookies", JSONArray().apply {
            cookies.forEach { stored ->
                put(JSONObject().apply {
                    put("name", stored.name)
                    put("value", stored.value)
                    putNullable("originUrl", stored.originUrl)
                    put("hostOnly", stored.hostOnly)
                    putNullable("domain", stored.domain)
                    putNullable("path", stored.path)
                    put("secure", stored.secure)
                    put("version", stored.version)
                    putNullable("expiresAtMillis", stored.expiresAtMillis)
                })
            }
        })
    }

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }

    private companion object {
        const val PREFERENCES_NAME = "find_my_secure_session"
        const val IV_KEY = "session_iv"
        const val DATA_KEY = "session_data"
        const val KEY_ALIAS = "librepods.findmy.apple.web.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun decodeSession(json: JSONObject): AppleWebSession {
            val cookiesJson = json.optJSONArray("cookies") ?: JSONArray()
            val cookies = buildList {
                for (index in 0 until cookiesJson.length()) {
                    val item = cookiesJson.getJSONObject(index)
                    add(
                        StoredFindMyCookie(
                            name = item.getString("name"),
                            value = item.getString("value"),
                            originUrl = item.optNullableString("originUrl"),
                            hostOnly = item.optBoolean(
                                "hostOnly",
                                item.optNullableString("originUrl") != null,
                            ),
                            domain = item.optNullableString("domain"),
                            path = item.optNullableString("path"),
                            secure = item.optBoolean("secure"),
                            version = item.optInt("version", 0),
                            expiresAtMillis = item.optNullableLong("expiresAtMillis"),
                        ),
                    )
                }
            }
            return AppleWebSession(
                appleId = json.getString("appleId"),
                clientId = json.getString("clientId"),
                dsid = json.optNullableString("dsid"),
                accountCountry = json.optNullableString("accountCountry"),
                sessionId = json.optNullableString("sessionId"),
                sessionToken = json.optNullableString("sessionToken"),
                trustToken = json.optNullableString("trustToken"),
                scnt = json.optNullableString("scnt"),
                findMeUrl = json.optNullableString("findMeUrl"),
                cookies = cookies,
                deviceSerials = json.optJSONObject("deviceSerials")?.let { serials ->
                    buildMap {
                        serials.keys().forEach { deviceId ->
                            serials.optString(deviceId).trim()
                                .takeIf(String::isNotBlank)
                                ?.let { serial -> put(deviceId, serial) }
                        }
                    }
                }.orEmpty(),
            )
        }

        private fun JSONObject.optNullableString(name: String): String? =
            if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

        private fun JSONObject.optNullableLong(name: String): Long? =
            if (isNull(name) || !has(name)) null else optLong(name)
    }
}
