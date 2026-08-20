package me.kavishdevar.librepods.features.findmy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * User-facing Find My presentation settings. These are ordinary preferences, not Apple
 * credentials or owner keys; the sensitive sessions remain in their existing secure stores.
 */
internal class FindMyDisplayStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(accountId: String): FindMyDisplaySettings {
        val raw = preferences.getString(profileKey(accountId), null) ?: return FindMyDisplaySettings()
        return runCatching { decode(JSONObject(raw)) }.getOrDefault(FindMyDisplaySettings())
    }

    @Synchronized
    fun save(accountId: String, settings: FindMyDisplaySettings) {
        if (accountId.isBlank()) return
        preferences.edit()
            .putString(profileKey(accountId), encode(settings).toString())
            .apply()
    }

    fun newGroup(name: String, settings: FindMyDisplaySettings): Pair<FindMyDisplayGroup, FindMyDisplaySettings> {
        val group = FindMyDisplayGroup(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(MAX_GROUP_NAME_LENGTH),
            sortOrder = settings.groups.size,
        )
        return group to settings.copy(groups = settings.groups + group)
    }

    private fun profileKey(accountId: String): String =
        "profile_" + sha256(accountId.trim())

    private companion object {
        const val PREFERENCES_NAME = "find_my_display_preferences"
        const val VERSION = 1
        const val MAX_GROUP_NAME_LENGTH = 48
        const val MAX_CUSTOM_NAME_LENGTH = 80

        fun encode(settings: FindMyDisplaySettings): JSONObject = JSONObject().apply {
            put("version", VERSION)
            put("groups", JSONArray().apply {
                settings.groups.forEach { group ->
                    put(JSONObject().apply {
                        put("id", group.id)
                        put("name", group.name)
                        put("sortOrder", group.sortOrder)
                    })
                }
            })
            put("entries", JSONArray().apply {
                settings.entries.forEach { (key, entry) ->
                    put(JSONObject().apply {
                        put("key", key)
                        entry.customName?.let { put("customName", it) }
                        put("visible", entry.visible)
                        entry.groupId?.let { put("groupId", it) }
                    })
                }
            })
            put("links", JSONArray().apply {
                settings.links.forEach { (left, right) ->
                    put(JSONObject().put("left", left).put("right", right))
                }
            })
            put("showCombineNotice", settings.showCombineNotice)
            put("showHiddenDevices", settings.showHiddenDevices)
            put("sortByRecent", settings.sortByRecent)
        }

        fun decode(json: JSONObject): FindMyDisplaySettings {
            check(json.optInt("version", 0) == VERSION) { "Unsupported Find My display settings" }
            val groups = buildList {
                val array = json.optJSONArray("groups") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    if (id.isNotBlank() && name.isNotBlank()) {
                        add(
                            FindMyDisplayGroup(
                                id = id,
                                name = name.take(MAX_GROUP_NAME_LENGTH),
                                sortOrder = item.optInt("sortOrder", index),
                            ),
                        )
                    }
                }
            }.sortedBy { it.sortOrder }

            val entries = linkedMapOf<String, FindMyDisplayEntry>()
            val entriesArray = json.optJSONArray("entries") ?: JSONArray()
            for (index in 0 until entriesArray.length()) {
                val item = entriesArray.optJSONObject(index) ?: continue
                val key = item.optString("key").trim()
                if (key.isBlank()) continue
                entries[key] = FindMyDisplayEntry(
                    customName = item.optString("customName").trim()
                        .takeIf(String::isNotBlank)?.take(MAX_CUSTOM_NAME_LENGTH),
                    visible = item.optBoolean("visible", true),
                    groupId = item.optString("groupId").trim().takeIf(String::isNotBlank),
                )
            }

            val links = linkedMapOf<String, String>()
            val linksArray = json.optJSONArray("links") ?: JSONArray()
            for (index in 0 until linksArray.length()) {
                val item = linksArray.optJSONObject(index) ?: continue
                val left = item.optString("left").trim()
                val right = item.optString("right").trim()
                if (left.isNotBlank() && right.isNotBlank() && left != right) {
                    links[left] = right
                }
            }
            return FindMyDisplaySettings(
                groups = groups,
                entries = entries,
                links = links,
                showCombineNotice = json.optBoolean("showCombineNotice", true),
                showHiddenDevices = json.optBoolean("showHiddenDevices", false),
                sortByRecent = json.optBoolean("sortByRecent", true),
            )
        }

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
