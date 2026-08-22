package me.kavishdevar.librepods.presentation.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindMyMapUiPolicyTest {
    @Test
    fun validSelectedKeyIsPreservedAcrossReorder() {
        assertEquals(
            "network:b",
            FindMyMapUiPolicy.normalizeSelectedKey(
                selectedKey = "network:b",
                locatedKeys = listOf("web:c", "network:b", "web:a"),
            ),
        )
    }

    @Test
    fun staleOrFilteredKeyFallsBackToFirstLocatedKey() {
        assertEquals(
            "web:a",
            FindMyMapUiPolicy.normalizeSelectedKey(
                selectedKey = "network:removed",
                locatedKeys = listOf("web:a", "network:b"),
            ),
        )
        assertNull(FindMyMapUiPolicy.normalizeSelectedKey("web:a", emptyList()))
    }

    @Test
    fun explicitSelectionRequestsFocus() {
        val previous = FindMyFocusedDeviceSnapshot(
            key = "web:a",
            coordinate = FindMyMapCoordinate(50.0, 4.0),
        )
        val current = FindMyFocusedDeviceSnapshot(
            key = "network:b",
            coordinate = FindMyMapCoordinate(51.0, 5.0),
        )

        assertTrue(
            FindMyMapUiPolicy.shouldFocusCamera(
                previous = previous,
                current = current,
                explicitSelectionChanged = true,
            ),
        )
        assertFalse(
            FindMyMapUiPolicy.shouldFocusCamera(
                previous = previous,
                current = current,
                explicitSelectionChanged = false,
            ),
        )
    }

    @Test
    fun selectedDeviceCoordinateUpdateFocusesButUnrelatedUpdateDoesNot() {
        val selectedBefore = FindMyFocusedDeviceSnapshot(
            key = "web:a",
            coordinate = FindMyMapCoordinate(50.0, 4.0),
        )
        val selectedAfter = FindMyFocusedDeviceSnapshot(
            key = "web:a",
            coordinate = FindMyMapCoordinate(50.1, 4.1),
        )
        assertTrue(
            FindMyMapUiPolicy.shouldFocusCamera(
                previous = selectedBefore,
                current = selectedAfter,
                explicitSelectionChanged = false,
            ),
        )

        val unchangedSelected = selectedBefore.copy()
        assertFalse(
            FindMyMapUiPolicy.shouldFocusCamera(
                previous = selectedBefore,
                current = unchangedSelected,
                explicitSelectionChanged = false,
            ),
        )
    }

    @Test
    fun viewportSaveRestorePreservesCenterAndZoom() {
        val saved = FindMyViewportState(
            latitude = 50.8467,
            longitude = 4.3525,
            zoom = 14.75,
        )
        val encoded = FindMyMapUiPolicy.saveViewport(saved)
        assertEquals(listOf(50.8467, 4.3525, 14.75), encoded)
        assertEquals(saved, FindMyMapUiPolicy.restoreViewport(encoded))
        assertNull(FindMyMapUiPolicy.restoreViewport(null))
    }
}
