package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class RtBuddyHeartRateStartupTest {
    @Test
    fun diagnosticStrategyTriesDirectStartupBeforeHostLibAssistedStartup() {
        assertEquals(HeartRateStartupStrategy.DIRECT, HeartRateStartupStrategy.forAttempt(1))
        assertEquals(
            HeartRateStartupStrategy.HOSTLIB_ASSISTED,
            HeartRateStartupStrategy.forAttempt(2)
        )
        assertEquals(
            HeartRateStartupStrategy.HOSTLIB_ASSISTED,
            HeartRateStartupStrategy.forAttempt(3)
        )
    }

    @Test
    fun decoderReportsStructuralMetadataDiagnosticsWithoutRawPayload() {
        val decoder = RtBuddyHeartRateDecoder()

        val result = decoder.feed(
            serviceMetadataFrame(serviceId = 20, name = "HeartRateService")
        )

        assertEquals(1, result.rtBuddyFrameCount)
        assertEquals(1, result.frameKinds[RtBuddyFrameKind.METADATA])
        assertEquals(1, result.logTypes[1])
        assertTrue(result.samples.isEmpty())
    }

    @Test
    fun decoderReportsMalformedCompleteRtBuddyFrame() {
        val decoder = RtBuddyHeartRateDecoder()
        val malformed = hex("04 00 04 00 17 00 00 00 10 00 01 00 ff")

        val result = decoder.feed(malformed)

        assertEquals(1, result.rtBuddyFrameCount)
        assertEquals(1, result.frameKinds[RtBuddyFrameKind.MALFORMED])
        assertTrue(result.samples.isEmpty())
    }

    @Test
    fun missingHostLibAcknowledgementDoesNotBlockLegacyCompatibleStartup() {
        val outcome = HostLibHidInitializationOutcome(
            serviceId = 19,
            sent = true,
            acknowledged = false
        )

        assertTrue(outcome.advertised)
        assertTrue(outcome.canContinue)
    }

    @Test
    fun hostLibSendFailureStillBlocksStartup() {
        val outcome = HostLibHidInitializationOutcome(
            serviceId = 19,
            sent = false,
            acknowledged = false
        )

        assertFalse(outcome.canContinue)
    }

    @Test
    fun absentHostLibServiceKeepsLegacyStartupAvailable() {
        val outcome = HostLibHidInitializationOutcome(
            serviceId = null,
            sent = false,
            acknowledged = false
        )

        assertFalse(outcome.advertised)
        assertTrue(outcome.canContinue)
    }

    @Test
    fun hostLibHidInitializationUsesObservedPayloadAndSharedSequence() {
        val frames = RtBuddyHeartRateControlFrames(initialSequence = 44)

        assertArrayEquals(
            hex("04 00 04 00 17 00 00 00 10 00 0f 00 08 2c 42 0b 08 13 10 02 1a 05 02 00 00 00 00"),
            frames.initializeHostLibHid(serviceId = 19)
        )
        assertArrayEquals(
            hex("04 00 04 00 17 00 00 00 10 00 0f 00 08 2d 42 0b 08 14 10 02 1a 05 01 40 42 0f 00"),
            frames.start(serviceId = 20)
        )
    }

    @Test
    fun decoderRecordsObservedServiceAcknowledgement() {
        val decoder = RtBuddyHeartRateDecoder()

        decoder.feed(
            hex("04 00 04 00 17 00 00 00 10 00 09 00 08 dc 07 10 01 4a 02 08 13")
        )

        assertEquals(1, decoder.acknowledgementCount(serviceId = 19))
        assertEquals(0, decoder.acknowledgementCount(serviceId = 20))
    }

    @Test
    fun transportReconnectRetainsObservedServiceRoles() {
        val decoder = RtBuddyHeartRateDecoder()
        decoder.feed(serviceMetadataFrame(serviceId = 20, name = "HeartRateService"))
        decoder.feed(serviceMetadataFrame(serviceId = 19, name = "HostLibHID"))

        decoder.resetForTransportReconnect()

        val resolution = decoder.heartRateServiceResolution()
        assertEquals(20, resolution.serviceId)
        assertTrue(resolution.discoveredFromMetadata)
        assertEquals(19, decoder.discoveredHostLibHidServiceId())
        assertEquals(20, decoder.heartRateServiceIdForControl())
    }

    @Test
    fun serviceResolutionWaitsForHostLibHidWhenMetadataIsSplit() = runBlocking {
        val decoder = RtBuddyHeartRateDecoder()
        var elapsedMillis = 0L
        var hostMetadataFed = false
        decoder.feed(serviceMetadataFrame(serviceId = 20, name = "HeartRateService"))

        val resolved = waitForHeartRateServiceResolution(
            decoder = decoder,
            timeoutMillis = 1_000L,
            elapsedRealtimeMillis = { elapsedMillis },
            pause = { duration ->
                elapsedMillis += duration
                if (!hostMetadataFed) {
                    hostMetadataFed = true
                    decoder.feed(serviceMetadataFrame(serviceId = 19, name = "HostLibHID"))
                }
            }
        )

        assertTrue(resolved)
        assertTrue(hostMetadataFed)
        assertEquals(20, decoder.discoveredHeartRateServiceId())
        assertEquals(19, decoder.discoveredHostLibHidServiceId())
    }

    private fun serviceMetadataFrame(serviceId: Int, name: String): ByteArray {
        val metadata = name.encodeToByteArray()
        val serviceRecord = byteArrayOf(0x08, serviceId.toByte(), 0x12, metadata.size.toByte()) +
            metadata
        val body = byteArrayOf(
            0x08, 0x01,
            0x10, 0x01,
            0x2a, serviceRecord.size.toByte()
        ) + serviceRecord
        return hex("04 00 04 00 17 00 00 00 10 00") +
            byteArrayOf(body.size.toByte(), 0x00) +
            body
    }

    private fun hex(value: String): ByteArray = value
        .split(' ')
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
