package me.kavishdevar.librepods.features.findmy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class FindMyDisplayUiState(
    val accountId: String = "",
    val settings: FindMyDisplaySettings = FindMyDisplaySettings(),
)

internal class FindMyDisplayViewModel(application: Application) : AndroidViewModel(application) {
    private val store = FindMyDisplayStore(application)
    private val _uiState = MutableStateFlow(FindMyDisplayUiState())
    val uiState: StateFlow<FindMyDisplayUiState> = _uiState.asStateFlow()

    fun setAccount(accountId: String) {
        val normalized = accountId.trim()
        if (normalized == _uiState.value.accountId) return
        _uiState.value = FindMyDisplayUiState(
            accountId = normalized,
            settings = if (normalized.isBlank()) FindMyDisplaySettings() else store.load(normalized),
        )
    }

    fun rename(key: String, name: String) = updateEntry(key) { entry ->
        entry.copy(customName = name.trim().take(80).takeIf(String::isNotBlank))
    }

    fun rename(keys: Collection<String>, name: String) {
        val normalizedKeys = keys.filter(String::isNotBlank).toSet()
        val clean = name.trim().take(80).takeIf(String::isNotBlank)
        if (normalizedKeys.isEmpty()) return
        val current = _uiState.value
        val entries = current.settings.entries.toMutableMap()
        normalizedKeys.forEach { key ->
            entries[key] = (entries[key] ?: FindMyDisplayEntry()).copy(customName = clean)
        }
        commit(current.settings.copy(entries = entries))
    }

    fun setVisible(key: String, visible: Boolean) = updateEntry(key) { it.copy(visible = visible) }

    fun updateLibraryPreferences(
        showCombineNotice: Boolean? = null,
        showHiddenDevices: Boolean? = null,
        sortByRecent: Boolean? = null,
    ) {
        val current = _uiState.value
        commit(
            current.settings.copy(
                showCombineNotice = showCombineNotice ?: current.settings.showCombineNotice,
                showHiddenDevices = showHiddenDevices ?: current.settings.showHiddenDevices,
                sortByRecent = sortByRecent ?: current.settings.sortByRecent,
            ),
        )
    }

    fun assignGroup(key: String, groupId: String?) = updateEntry(key) { it.copy(groupId = groupId) }

    fun assignGroup(keys: Collection<String>, groupId: String?) {
        val normalizedKeys = keys.filter(String::isNotBlank).toSet()
        if (normalizedKeys.isEmpty()) return
        val current = _uiState.value
        val entries = current.settings.entries.toMutableMap()
        normalizedKeys.forEach { key ->
            entries[key] = (entries[key] ?: FindMyDisplayEntry()).copy(groupId = groupId)
        }
        commit(current.settings.copy(entries = entries))
    }

    fun createGroup(name: String, memberKeys: Collection<String> = emptyList()): String? {
        val clean = name.trim().take(48)
        if (clean.isBlank()) return null
        val current = _uiState.value
        val (group, updated) = store.newGroup(clean, current.settings)
        val entries = updated.entries.toMutableMap()
        memberKeys.filter(String::isNotBlank).forEach { key ->
            entries[key] = (entries[key] ?: FindMyDisplayEntry()).copy(groupId = group.id)
        }
        commit(updated.copy(entries = entries))
        return group.id
    }

    fun renameGroup(groupId: String, name: String) {
        val clean = name.trim().take(48)
        if (groupId.isBlank() || clean.isBlank()) return
        val current = _uiState.value
        if (current.settings.groups.none { it.id == groupId }) return
        commit(
            current.settings.copy(
                groups = current.settings.groups.map { group ->
                    if (group.id == groupId) group.copy(name = clean) else group
                },
            ),
        )
    }

    fun deleteGroup(groupId: String) {
        val current = _uiState.value
        val updated = current.settings.copy(
            groups = current.settings.groups.filterNot { it.id == groupId },
            entries = current.settings.entries.mapValues { (_, entry) ->
                if (entry.groupId == groupId) entry.copy(groupId = null) else entry
            },
        )
        commit(updated)
    }

    fun link(left: String, right: String) {
        if (left == right) return
        val current = _uiState.value
        val links = current.settings.links.toMutableMap()
        links.entries.removeIf { (key, value) -> key == left || value == left || key == right || value == right }
        links[left] = right
        commit(current.settings.copy(links = links))
    }

    fun unlink(keys: Set<String>) {
        if (keys.isEmpty()) return
        val current = _uiState.value
        val updatedLinks = current.settings.links.filterKeys { it !in keys }
            .filterValues { it !in keys }
        commit(current.settings.copy(links = updatedLinks))
    }

    fun resetEntry(key: String) {
        val current = _uiState.value
        commit(current.settings.copy(entries = current.settings.entries - key))
    }

    private fun updateEntry(key: String, transform: (FindMyDisplayEntry) -> FindMyDisplayEntry) {
        if (key.isBlank()) return
        val current = _uiState.value
        val updatedEntry = transform(current.settings.entries[key] ?: FindMyDisplayEntry())
        commit(current.settings.copy(entries = current.settings.entries + (key to updatedEntry)))
    }

    private fun commit(settings: FindMyDisplaySettings) {
        val account = _uiState.value.accountId
        _uiState.value = _uiState.value.copy(settings = settings)
        if (account.isNotBlank()) store.save(account, settings)
    }
}
