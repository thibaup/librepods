package me.kavishdevar.librepods.features.findmy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindMyDisplayModelsTest {
    @Test
    fun timestampNormalizerConvertsSecondsAndLeavesMillisecondsAlone() {
        assertEquals(1_700_000_000_000L, normalizeFindMyTimestampMillis(1_700_000_000L))
        assertEquals(1_700_000_000_000L, normalizeFindMyTimestampMillis(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, normalizeFindMyTimestampMillis(1_700_000_000_000_000L))
        assertEquals(null, normalizeFindMyTimestampMillis(0L))
    }

    @Test
    fun similarLabelsRemainSeparateWithoutAnExplicitLink() {
        val web = device(id = "web-1", name = "AirPods")
        val network = network(beaconId = "beacon-1", label = "AirPods")

        val rows = buildFindMyDisplayItems(listOf(web), listOf(network))

        assertEquals(2, rows.size)
        assertTrue(rows.none { it.isLinked })
    }

    @Test
    fun identicalTextInDifferentNamespacesDoesNotAutomaticallyMerge() {
        val rows = buildFindMyDisplayItems(
            listOf(device(id = "same-id", name = "AirPods")),
            listOf(network(beaconId = "same-id", label = "Different label")),
        )

        assertEquals(2, rows.size)
        assertTrue(rows.none { it.isLinked })
    }

    @Test
    fun explicitLinkMergesDifferentNamespaces() {
        val web = device(id = "web-1", name = "AirPods")
        val network = network(beaconId = "beacon-1", label = "My pods")

        val rows = buildFindMyDisplayItems(
            listOf(web),
            listOf(network),
            links = mapOf(findMyNetworkKey(network.beaconId) to findMyWebKey(web.id)),
        )

        assertEquals(1, rows.size)
        assertTrue(rows.single().isLinked)
        assertEquals("AirPods", rows.single().defaultName)
    }

    @Test
    fun serialNumbersAreRetainedAndExposedForEachSource() {
        val web = device(id = "web-1", name = "AirPods").copy(serialNumber = "APPLE-SERIAL-1")
        val network = network(beaconId = "beacon-1", label = "My pods")
            .copy(serialNumber = "NETWORK-SERIAL-1")

        val rows = buildFindMyDisplayItems(listOf(web), listOf(network))

        assertEquals("APPLE-SERIAL-1", rows.first { it.webDevice != null }.serialNumber)
        assertEquals("NETWORK-SERIAL-1", rows.first { it.networkAccessory != null }.serialNumber)
    }

    @Test
    fun linkedRowKeepsBothSerialsInSourceOrder() {
        val web = device(id = "web-1", name = "AirPods").copy(serialNumber = "APPLE-SERIAL-1")
        val network = network(beaconId = "beacon-1", label = "My pods")
            .copy(serialNumber = "NETWORK-SERIAL-1")

        val row = buildFindMyDisplayItems(
            listOf(web),
            listOf(network),
            links = mapOf(findMyNetworkKey(network.beaconId) to findMyWebKey(web.id)),
        ).single()

        assertEquals(listOf("APPLE-SERIAL-1", "NETWORK-SERIAL-1"), row.serialNumbers)
    }

    @Test
    fun mergedRowUsesTheNewestSourceLocation() {
        val web = device(id = "web-1", name = "AirPods", timestamp = 1_000L)
        val network = network(beaconId = "beacon-1", label = "My pods", timestamp = 2_000L)
        val rows = buildFindMyDisplayItems(
            listOf(web),
            listOf(network),
            links = mapOf(findMyNetworkKey(network.beaconId) to findMyWebKey(web.id)),
        )

        assertEquals(2_000L, rows.single().location?.timestampMillis)
        assertEquals(FindMyDisplaySource.FIND_MY_NETWORK, rows.single().locationSource)
    }

    @Test
    fun linkedRowComparesNormalizedEpochUnits() {
        val web = device(
            id = "web-1",
            name = "iPhone",
            timestamp = normalizeFindMyTimestampMillis(1_700_000_000L),
        )
        val network = network(
            beaconId = "beacon-1",
            label = "iPhone",
            timestamp = normalizeFindMyTimestampMillis(1_700_000_100L),
        )

        val row = buildFindMyDisplayItems(
            listOf(web),
            listOf(network),
            links = mapOf(findMyNetworkKey(network.beaconId) to findMyWebKey(web.id)),
        ).single()

        assertEquals(1_700_000_100_000L, row.location?.timestampMillis)
        assertEquals(FindMyDisplaySource.FIND_MY_NETWORK, row.locationSource)
    }

    @Test
    fun duplicateWebIdsKeepTheNewestLocationRegardlessOfResponseOrder() {
        val newest = device(id = "web-1", name = "AirPods", timestamp = 9_000L)
        val stale = device(id = "web-1", name = "AirPods", timestamp = 1_000L)

        val row = buildFindMyDisplayItems(listOf(newest, stale), emptyList()).single()

        assertEquals(9_000L, row.location?.timestampMillis)
    }

    @Test
    fun duplicateNetworkIdsKeepTheNewestLocationRegardlessOfResponseOrder() {
        val newest = network(beaconId = "beacon-1", label = "AirPods", timestamp = 9_000L)
        val stale = network(beaconId = "beacon-1", label = "AirPods", timestamp = 1_000L)

        val row = buildFindMyDisplayItems(emptyList(), listOf(newest, stale)).single()

        assertEquals(9_000L, row.location?.timestampMillis)
    }

    @Test
    fun duplicateWithCoordinatesBeatsMissingLocationWhenBothLackATimestamp() {
        val withCoordinates = device(id = "web-1", name = "Located").copy(
            location = location(1L).copy(timestampMillis = null),
        )
        val missing = device(id = "web-1", name = "Missing")

        val row = buildFindMyDisplayItems(listOf(withCoordinates, missing), emptyList()).single()

        assertEquals(50.0, row.location?.latitude)
    }

    @Test
    fun equalTimestampDuplicatesResolveDeterministically() {
        val alpha = device(id = "web-1", name = "Alpha", timestamp = 9_000L)
        val zulu = device(id = "web-1", name = "Zulu", timestamp = 9_000L)

        val forward = buildFindMyDisplayItems(listOf(alpha, zulu), emptyList()).single()
        val reverse = buildFindMyDisplayItems(listOf(zulu, alpha), emptyList()).single()

        assertEquals(forward.defaultName, reverse.defaultName)
        assertEquals("Zulu", forward.defaultName)
    }

    @Test
    fun deviceRefreshDoesNotReplaceCachedLocationWithAnEmptyWindow() {
        val cached = location(9_000L)

        assertEquals(cached, newestLocation(cached, null))
        assertEquals(9_000L, newestLocation(location(1_000L), cached)?.timestampMillis)
    }

    @Test
    fun sourceKeysAreNamespaced() {
        assertFalse(findMyWebKey("abc") == findMyNetworkKey("abc"))
        assertEquals("web:abc", findMyWebKey("abc"))
        assertEquals("network:abc", findMyNetworkKey("abc"))
    }

    private fun device(id: String, name: String, timestamp: Long? = null) = FindMyDevice(
        id = id,
        name = name,
        displayName = "AirPods",
        modelName = "AirPods",
        rawModel = null,
        batteryLevel = null,
        batteryStatus = null,
        deviceStatus = null,
        isAccessory = true,
        location = timestamp?.let { location(it) },
    )

    private fun network(beaconId: String, label: String, timestamp: Long? = null) =
        FindMyNetworkAccessory(
            beaconId = beaconId,
            label = label,
            location = timestamp?.let { location(it) },
            reportCount = if (timestamp == null) 0 else 1,
        )

    private fun location(timestamp: Long) = FindMyLocation(
        latitude = 50.0,
        longitude = 4.0,
        horizontalAccuracyMeters = 10.0,
        timestampMillis = timestamp,
        isOld = false,
        positionType = null,
    )
}
