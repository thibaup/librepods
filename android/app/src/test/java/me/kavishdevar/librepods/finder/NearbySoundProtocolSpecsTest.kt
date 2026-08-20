package me.kavishdevar.librepods.finder

import me.kavishdevar.librepods.bluetooth.BLEManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class NearbySoundProtocolSpecsTest {
    @Test
    fun `DULT is preferred when both services are present`() {
        val candidates = NearbySoundProtocolSpecs.candidates(
            listOf(
                UUID.fromString("0000FD44-0000-1000-8000-00805F9B34FB"),
                NearbySoundProtocolSpecs.dultServiceUuid
            )
        )

        assertEquals(
            listOf(NearbySoundProtocol.DULT, NearbySoundProtocol.APPLE_FIND_MY),
            candidates.map { it.protocol }
        )
    }

    @Test
    fun `protocol opcodes match their little endian wire format`() {
        val dult = NearbySoundProtocolSpecs.candidates(
            listOf(NearbySoundProtocolSpecs.dultServiceUuid)
        ).single()
        val findMy = NearbySoundProtocolSpecs.candidates(
            listOf(UUID.fromString("0000FD44-0000-1000-8000-00805F9B34FB"))
        ).single()

        assertArrayEquals(byteArrayOf(0x00, 0x03), dult.startOpcode)
        assertArrayEquals(byteArrayOf(0x01, 0x03), dult.stopOpcode)
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x03), findMy.startOpcode)
        assertArrayEquals(byteArrayOf(0x01, 0x01, 0x03), findMy.stopOpcode)
    }

    @Test
    fun `unrelated services are ignored`() {
        assertEquals(
            emptyList<NearbySoundProtocolSpec>(),
            NearbySoundProtocolSpecs.candidates(listOf(UUID.randomUUID()))
        )
    }

    @Test
    fun `offline AirPods manufacturer data is recognized without matching other Apple devices`() {
        assertEquals(
            true,
            BLEManager.isOfflineAirPodsFinderAdvertisement(
                byteArrayOf(0x12, 0x19, 0x18, 0x00)
            )
        )
        assertEquals(
            false,
            BLEManager.isOfflineAirPodsFinderAdvertisement(
                byteArrayOf(0x12, 0x19, 0x10, 0x00)
            )
        )
        assertEquals(
            false,
            BLEManager.isOfflineAirPodsFinderAdvertisement(byteArrayOf(0x12, 0x19))
        )
    }
}
