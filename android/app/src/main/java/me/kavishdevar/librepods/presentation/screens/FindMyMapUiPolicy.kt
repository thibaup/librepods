package me.kavishdevar.librepods.presentation.screens

/** Pure state used to save/restore the osmdroid viewport without depending on Compose or osmdroid. */
internal data class FindMyViewportState(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
)

/** Pure location snapshot used by camera-focus policy. */
internal data class FindMyMapCoordinate(
    val latitude: Double,
    val longitude: Double,
)

internal data class FindMyFocusedDeviceSnapshot(
    val key: String?,
    val coordinate: FindMyMapCoordinate?,
)

internal object FindMyMapUiPolicy {
    /**
     * Stable-key normalization for the located, visible device list.
     *
     * A valid key survives sorting/reordering. A stale, filtered, or absent key falls back to the
     * first currently located key. An empty located list has no selection.
     */
    fun normalizeSelectedKey(
        selectedKey: String?,
        locatedKeys: List<String>,
    ): String? {
        if (locatedKeys.isEmpty()) return null
        return selectedKey?.takeIf(locatedKeys::contains) ?: locatedKeys.first()
    }

    /**
     * Camera motion is intentionally narrow: an explicit selection always focuses, and a location
     * update follows only when it belongs to the already-focused device. Passive normalization,
     * reorder, filtering, and updates for other devices do not move the camera.
     */
    fun shouldFocusCamera(
        previous: FindMyFocusedDeviceSnapshot,
        current: FindMyFocusedDeviceSnapshot,
        explicitSelectionChanged: Boolean,
    ): Boolean {
        if (current.key == null || current.coordinate == null) return false
        if (explicitSelectionChanged) return true
        return previous.key == current.key &&
            previous.coordinate != null &&
            previous.coordinate != current.coordinate
    }

    /** Encode viewport state into primitive saveable values without Compose or Android types. */
    fun saveViewport(viewport: FindMyViewportState?): List<Double>? = viewport?.let {
        listOf(it.latitude, it.longitude, it.zoom)
    }

    /** Restore only a complete finite viewport snapshot. */
    fun restoreViewport(saved: List<Double>?): FindMyViewportState? {
        if (saved == null || saved.size != 3 || saved.any { !it.isFinite() }) return null
        return FindMyViewportState(
            latitude = saved[0],
            longitude = saved[1],
            zoom = saved[2],
        )
    }
}
