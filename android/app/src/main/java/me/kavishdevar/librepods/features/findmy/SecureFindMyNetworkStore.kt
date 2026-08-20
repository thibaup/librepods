package me.kavishdevar.librepods.features.findmy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class StoredFindMyNetworkAccessory(
    val beaconId: String,
    val label: String,
    val accessoryJson: String,
    /** Cached serial-like identity, kept inside the encrypted network state. */
    val serialNumber: String? = null,
)

internal data class StoredFindMyNetworkState(
    val appleId: String,
    val accountJson: String,
    val accessories: List<StoredFindMyNetworkAccessory>,
)

/**
 * Encrypted storage for FindMy.py's account state and recovered beacon keys.
 *
 * FindMy.py's serialized account contains long-lived Apple session material and may contain
 * the account password. Accessory JSON contains rolling private keys. They therefore share a
 * dedicated non-exportable Android Keystore key and are never written to ordinary preferences.
 */
internal class SecureFindMyNetworkStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(state: StoredFindMyNetworkState) {
        val plaintext = JSONObject().apply {
            put("version", 1)
            put("appleId", state.appleId)
            put("accountJson", state.accountJson)
            put("accessories", JSONArray().apply {
                state.accessories.forEach { accessory ->
                    put(JSONObject().apply {
                        put("beaconId", accessory.beaconId)
                        put("label", accessory.label)
                        put("accessoryJson", accessory.accessoryJson)
                        put("serialNumber", accessory.serialNumber ?: JSONObject.NULL)
                    })
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plaintext)
            val committed = preferences.edit()
                .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(DATA_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
            check(committed) { "Encrypted Find My network state could not be committed" }
        } finally {
            plaintext.fill(0)
        }
    }

    fun load(): StoredFindMyNetworkState? {
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
                decode(JSONObject(plaintext.toString(Charsets.UTF_8)))
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            // Keep the ciphertext: a transient Keystore problem must not silently destroy
            // recovered keys. The UI can offer an explicit reset if restoration keeps failing.
            null
        }
    }

    fun hasEncryptedState(): Boolean =
        preferences.contains(IV_KEY) && preferences.contains(DATA_KEY)

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

    private companion object {
        const val PREFERENCES_NAME = "find_my_network_secure_state"
        const val IV_KEY = "state_iv"
        const val DATA_KEY = "state_data"
        const val KEY_ALIAS = "librepods.findmy.network.account-and-keys.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun decode(json: JSONObject): StoredFindMyNetworkState {
            check(json.optInt("version", 0) == 1) { "Unsupported Find My network state" }
            val array = json.optJSONArray("accessories") ?: JSONArray()
            val accessories = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        StoredFindMyNetworkAccessory(
                            beaconId = item.getString("beaconId"),
                            label = item.optString("label").ifBlank { "Unnamed accessory" },
                            accessoryJson = item.getString("accessoryJson"),
                            serialNumber = item.optString("serialNumber").trim().takeIf {
                                it.isNotBlank() && !it.equals("null", ignoreCase = true)
                            },
                        ),
                    )
                }
            }
            return StoredFindMyNetworkState(
                appleId = json.optString("appleId"),
                accountJson = json.getString("accountJson"),
                accessories = accessories,
            )
        }
    }
}
