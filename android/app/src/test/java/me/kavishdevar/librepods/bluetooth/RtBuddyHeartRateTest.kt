package me.kavishdevar.librepods.bluetooth

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtBuddyHeartRateTest {
    private fun decoder() = RtBuddyHeartRateDecoder(
        wallClockMillis = { 123L },
        elapsedRealtimeMillis = { 456L }
    )

    @Test
    fun discoversCapturedIos27HeartRateService() {
        val decoder = decoder()

        val result = decoder.feed(CAPTURED_METADATA)

        assertEquals(20, decoder.discoveredHeartRateServiceId())
        assertEquals(20, decoder.heartRateServiceIdForControl())
        assertEquals(0, result.relatedFrameCount)
        assertEquals(0, result.rejectedFrameCount)
        assertTrue(result.passthroughPackets.isEmpty())
        decoder.reset()
        assertNull(decoder.discoveredHeartRateServiceId())
        assertEquals(19, decoder.heartRateServiceIdForControl())
    }

    @Test
    fun buildsExactCapturedIos27StartFrame() {
        val actual = RtBuddyHeartRateControlFrames.buildFrame(
            serviceId = 20,
            sequence = 894,
            intervalMicros = 1_000_000
        )

        assertContentEquals(CAPTURED_START, actual)
        assertTrue(RtBuddyHeartRateControlFrames.isControlFrame(actual))
    }

    @Test
    fun controlSequenceIsMonotonicAndResetsPerConnection() {
        val controls = RtBuddyHeartRateControlFrames(initialSequence = 894)

        val firstStart = controls.start(serviceId = 20)
        val stop = controls.stop(serviceId = 20)
        controls.reset()
        val nextConnectionStart = controls.start(serviceId = 20)

        assertContentEquals(CAPTURED_START, firstStart)
        assertContentEquals(
            RtBuddyHeartRateControlFrames.buildFrame(20, 895, 0),
            stop
        )
        assertContentEquals(firstStart, nextConnectionStart)
    }

    @Test
    fun decodesCaptured71_130And83BpmReports() {
        val decoder = decoder()
        decoder.feed(CAPTURED_METADATA)

        val samples = listOf(CAPTURED_71, CAPTURED_130, CAPTURED_83).flatMap {
            decoder.feed(it).samples
        }

        assertEquals(listOf(71, 130, 83), samples.map { it.bpm })
        assertEquals(listOf(356, 1_897, 3_504), samples.map { it.sequence })
        assertTrue(samples.all { it.receivedAtMillis == 123L })
        assertTrue(samples.all { it.receivedAtElapsedRealtime == 456L })
    }

    @Test
    fun rejectsCapturedStartupStatusThenAcceptsAllowlistedStatus() {
        val decoder = decoder()
        decoder.feed(CAPTURED_METADATA)

        val startup = decoder.feed(CAPTURED_STARTUP_169)
        val validated = decoder.feed(CAPTURED_71)

        assertTrue(startup.samples.isEmpty())
        assertEquals(
            1,
            startup.rejectionReasons[HeartRateRejectionReason.UNRECOGNIZED_HEART_RATE_PAYLOAD]
        )
        assertEquals(71, validated.samples.single().bpm)
    }

    @Test
    fun metadataBindingPreventsService19FromBeingDecodedAsHeartRate() {
        val decoder = decoder()
        decoder.feed(CAPTURED_METADATA)

        val service19Lookalike = CAPTURED_71.copyOf().also { packet ->
            val serviceOffset = packet.indexOfSubsequence(hex("08141a12"))
            packet[serviceOffset + 1] = 0x13
        }

        assertTrue(decoder.feed(service19Lookalike).samples.isEmpty())
    }

    @Test
    fun refusesLegacyFallbackWhenMetadataAssigns19ToHostLibHid() {
        val decoder = decoder()

        decoder.feed(HOST_LIB_HID_19_METADATA)

        assertNull(decoder.discoveredHeartRateServiceId())
        assertNull(decoder.heartRateServiceIdForControl())
    }

    @Test
    fun hostLibHidExclusionCannotBeOverriddenLaterInTheConnection() {
        val decoder = decoder()

        decoder.feed(HEART_RATE_19_METADATA)
        assertEquals(19, decoder.discoveredHeartRateServiceId())
        decoder.feed(HOST_LIB_HID_19_METADATA)
        decoder.feed(HEART_RATE_19_METADATA)

        assertNull(decoder.discoveredHeartRateServiceId())
        assertNull(decoder.heartRateServiceIdForControl())
    }

    @Test
    fun waitsForMetadataBeforeUsingTheLegacyFallback() = runBlocking {
        val decoder = decoder()
        var now = 0L

        val resolved = waitForHeartRateServiceResolution(
            decoder = decoder,
            timeoutMillis = 1_500L,
            elapsedRealtimeMillis = { now },
            pause = { duration ->
                now += duration
                if (now == 50L) decoder.feed(CAPTURED_METADATA)
            }
        )

        assertTrue(resolved)
        assertEquals(50L, now)
        assertEquals(20, decoder.discoveredHeartRateServiceId())
    }

    @Test
    fun resolutionTimeoutFallsBackUnlessMetadataDeniesService19() = runBlocking {
        suspend fun resolve(decoder: RtBuddyHeartRateDecoder): Pair<Boolean, Long> {
            var now = 0L
            val result = waitForHeartRateServiceResolution(
                decoder = decoder,
                timeoutMillis = 1_500L,
                elapsedRealtimeMillis = { now },
                pause = { now += it }
            )
            return result to now
        }

        val fallback = resolve(decoder())
        val deniedDecoder = decoder().also { it.feed(HOST_LIB_HID_19_METADATA) }
        val denied = resolve(deniedDecoder)

        assertEquals(true to 1_500L, fallback)
        assertEquals(false to 1_500L, denied)
    }

    @Test
    fun legacyService19StillWorksWithoutMetadata() {
        val decoder = decoder()
        val service19Report = CAPTURED_71.copyOf().also { packet ->
            val serviceOffset = packet.indexOfSubsequence(hex("08141a12"))
            packet[serviceOffset + 1] = 0x13
        }

        assertEquals(71, decoder.feed(service19Report).samples.single().bpm)
    }

    @Test
    fun controlSessionRequiresAStartPinsItsServiceAndResetsPerConnection() {
        val decoder = decoder()
        val session = RtBuddyHeartRateControlSession(
            decoder = decoder,
            frames = RtBuddyHeartRateControlFrames(initialSequence = 894)
        )
        val packets = mutableListOf<ByteArray>()

        val prematureStop = session.sendStop { packet -> packets += packet; true }
        assertFalse(prematureStop.attempted)
        assertTrue(packets.isEmpty())

        val start = session.sendStart { packet -> packets += packet; true }
        decoder.feed(CAPTURED_METADATA)
        val failedStop = session.sendStop { packet -> packets += packet; false }
        val retriedStop = session.sendStop { packet -> packets += packet; true }
        val duplicateStop = session.sendStop { packet -> packets += packet; true }

        assertEquals(19, start.serviceId)
        assertEquals(19, failedStop.serviceId)
        assertEquals(19, retriedStop.serviceId)
        assertFalse(duplicateStop.attempted)
        assertContentEquals(
            RtBuddyHeartRateControlFrames.buildFrame(19, 894, 1_000_000),
            packets[0]
        )
        assertContentEquals(RtBuddyHeartRateControlFrames.buildFrame(19, 895, 0), packets[1])
        assertContentEquals(RtBuddyHeartRateControlFrames.buildFrame(19, 896, 0), packets[2])

        decoder.reset()
        session.reset()
        session.sendStart { packet -> packets += packet; true }
        assertContentEquals(
            RtBuddyHeartRateControlFrames.buildFrame(19, 894, 1_000_000),
            packets.last()
        )
    }

    @Test
    fun replaysEveryCapturedWorkoutReport() {
        val decoder = decoder()
        decoder.feed(CAPTURED_METADATA)
        val frames = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("ios27_workout_heart_rate_frames.hex")
        ).bufferedReader().useLines { lines ->
            lines.filter(String::isNotBlank).map(::hex).toList()
        }

        val results = frames.map(decoder::feed)
        val samples = results.flatMap(HeartRateDecodeResult::samples)

        assertEquals(82, frames.size)
        results.forEachIndexed { index, result ->
            if (index < 4) {
                assertTrue(result.samples.isEmpty(), "startup frame ${index + 1} was accepted")
                assertEquals(1, result.rejectedFrameCount, "startup frame ${index + 1}")
            } else {
                assertEquals(1, result.samples.size, "workout frame ${index + 1}")
                assertEquals(0, result.rejectedFrameCount, "workout frame ${index + 1}")
            }
        }
        assertEquals(82, results.sumOf(HeartRateDecodeResult::relatedFrameCount))
        assertEquals(4, results.sumOf(HeartRateDecodeResult::rejectedFrameCount))
        assertEquals(78, samples.size)
        assertEquals(71, samples.first().bpm)
        assertEquals(132, samples.maxOf(HeartRateSample::bpm))
        assertEquals(4, samples.count { it.bpm == 83 })
        assertTrue(samples.any { it.bpm == 88 })
        assertEquals(90, samples.last().bpm)
        val statusTransitions = frames.mapIndexedNotNull { index, frame ->
            val tail = frame.takeLast(3).joinToString("") { "%02x".format(it) }
            val previousTail = frames.getOrNull(index - 1)
                ?.takeLast(3)
                ?.joinToString("") { "%02x".format(it) }
            (index + 1 to tail).takeIf { tail != previousTail }
        }
        assertEquals(
            listOf(
                1 to "108281",
                2 to "100281",
                5 to "100000",
                39 to "100080",
                42 to "100000",
                57 to "100080",
                60 to "100000",
                81 to "208000",
                82 to "200000"
            ),
            statusTransitions
        )
        assertEquals(
            mapOf(39 to 126, 42 to 120, 57 to 112, 60 to 93, 81 to 88, 82 to 90),
            listOf(39, 42, 57, 60, 81, 82).associateWith {
                results[it - 1].samples.single().bpm
            }
        )
    }

    private companion object {
        val CAPTURED_START = hex(
            "04000400170000001000100008fe06420b081410021a050140420f00"
        )
        val CAPTURED_STARTUP_169 = hex(
            "040004001700000010001d0008b30110013a1608141a1201a914000002505f5e9d4505000001108281"
        )
        val CAPTURED_71 = hex(
            "040004001700000010001d0008e40210013a1608141a120147ae040002c09ec98b4605000000100000"
        )
        val CAPTURED_130 = hex(
            "040004001700000010001d0008e90e10013a1608141a120182b7230002a8a785c34d05000000100000"
        )
        val CAPTURED_83 = hex(
            "040004001700000010001d0008b01b10013a1608141a120153ec4a0002e05216d85605000000100000"
        )
        val HOST_LIB_HID_19_METADATA = hex(
            "040004001700000010001400080110012a0e0813120a486f73744c6962484944"
        )
        val HEART_RATE_19_METADATA = hex(
            "040004001700000010001a00080110012a140813121048656172745261746553657276696365"
        )

        // Exact 920-byte RTBuddy metadata frame captured at device-local 22:06:01.071.
        val CAPTURED_METADATA = hex(
            """
            040004001700000010008c03080910012add03081312d803d3000000050000810d0000094d61785265706f727453697a65010000400000044600000000000000100000094163636573736f7279536572766963650a000009486f73744c69624849440200180000094d756c7469706c65496e74657266616365456e61626c65640100000b0800000956656e646f72494440000004ac05000000000000100000095265706f727444657363726970746f723901008a0635ff0901a101c00600ff091aa101c00635ff0902a101850105200a0e031427ffffff7f75209501b1020600ff0923a10015002485010635ff090295457508810285020903950175088102c0c00635ff0904a101850205200a0e03150027ffffff7f75209501b1020600ff0923a10015002485030635ff090495027508810285040903950175088102c0c00635ff0906a101850305200a0e03150027ffffff7f75209501b1020600ff0923a10015002485050635ff090695027508810285060907950175088102c0c00635ff0908a101850405200a0e03150027ffffff7f750895efb1020600ff0923a10015002485070635ff090895ef750881028508090995ef75088102c0c00635ff0908a10185050635ff09081500250175089501b1020600ff0923a1001500250185090635ff0908950275088102c0c00100002aa503081412a003d3000000070000810d0000094d61785265706f727453697a650100004000000459020000000000000a0000094c6f636174696f6e494402004000000401000000000000001000000948656172745261746553657276696365090000094865617274526174650100000800000956656e646f72494440000004ac050000000000001b00000948494453657276696365416363657373456e7469746c656d656e74031e000009636f6d2e6170706c652e6869642e6865617274726174652d61636365737302001a000009484944446576696365416363657373456e7469746c656d656e7402001e000009636f6d2e6170706c652e6869642e6865617274726174652d6163636573730200100000095265706f727444657363726970746f727e00008a05200916a10185010a0e031427ffffff7f75209501b1020ab80426ff007508950181020615ff0a200195017508810226ff7f0615ff0a210195017510810275089501150125021a04012a050181000600ff0923a1001500240615ff0904950875088102060aff09129504750881028502060aff091396580275088102c0c00200
            """
        )

        fun hex(value: String): ByteArray {
            val compact = value.filterNot(Char::isWhitespace)
            require(compact.length % 2 == 0)
            return ByteArray(compact.length / 2) { index ->
                compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
            for (start in 0..size - needle.size) {
                if (needle.indices.all { this[start + it] == needle[it] }) return start
            }
            error("Subsequence not found")
        }
    }
}
