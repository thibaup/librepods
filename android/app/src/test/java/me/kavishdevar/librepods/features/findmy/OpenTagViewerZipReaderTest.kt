package me.kavishdevar.librepods.features.findmy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OpenTagViewerZipReaderTest {
    @Test
    fun `plain custom-accessory export is parsed`() {
        val parsed = OpenTagViewerZipReader.read(
            zip(
                "OPENTAGVIEWER.yml" to VALID_MANIFEST_0_0_3,
                "CustomAccessories/bike-123.json" to CUSTOM_ACCESSORY,
            ),
        )

        assertEquals(0, parsed.pairedAccessories.size)
        assertEquals(1, parsed.customAccessories.size)
        assertEquals("bike-123", parsed.customAccessories.single().identifier)
    }

    @Test
    fun `paired export keeps the optional alignment record`() {
        val parsed = OpenTagViewerZipReader.read(
            zip(
                "OPENTAGVIEWER.yml" to VALID_MANIFEST_0_0_2,
                "OwnedBeacons/$BEACON.plist" to "owned",
                "BeaconNamingRecord/$BEACON/$RECORD.plist" to "naming",
                "BeaconNamingRecord/$BEACON/$RECORD_2.plist" to "newer naming",
                "KeyAlignmentRecords/$BEACON/$RECORD.plist" to "alignment",
                "KeyAlignmentRecords/$BEACON/$RECORD_2.plist" to "newer alignment",
            ),
        )

        val accessory = parsed.pairedAccessories.single()
        assertEquals(BEACON, accessory.beaconId)
        assertEquals("owned", accessory.ownedBeaconPlist)
        assertEquals(
            listOf(
                OpenTagViewerPlistRecord(RECORD, "naming"),
                OpenTagViewerPlistRecord(RECORD_2, "newer naming"),
            ),
            accessory.namingRecords,
        )
        assertEquals(
            listOf(
                OpenTagViewerPlistRecord(RECORD, "alignment"),
                OpenTagViewerPlistRecord(RECORD_2, "newer alignment"),
            ),
            accessory.keyAlignmentRecords,
        )
    }

    @Test
    fun `exporter AES fixture opens with displayed code`() {
        val resource = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("opentagviewer_locked_bundle_fixture.zip"),
        )
        val parsed = resource.use { OpenTagViewerZipReader.read(it, "h4k2-9wmr-7tqx") }

        assertEquals(1, parsed.pairedAccessories.size)
        assertEquals("0FB0AEAC-C083-405E-A979-4AA6A73F5C56", parsed.pairedAccessories.single().beaconId)
    }

    @Test
    fun `exporter AES fixture asks for a code and distinguishes a wrong code`() {
        val locked = assertThrows(OpenTagViewerImportException::class.java) {
            fixture().use { OpenTagViewerZipReader.read(it) }
        }
        assertEquals(OpenTagViewerImportReason.LOCKED, locked.reason)

        val wrong = assertThrows(OpenTagViewerImportException::class.java) {
            fixture().use { OpenTagViewerZipReader.read(it, "0000-0000-0000") }
        }
        assertEquals(OpenTagViewerImportReason.WRONG_PASSCODE, wrong.reason)
    }

    @Test
    fun `passcode normalization matches exporter contract`() {
        assertEquals("H4K29WMR7TQX", OpenTagViewerBundlePasscode.normalise(" h4k2-9wmr_7tqx\n"))
        assertEquals("101", OpenTagViewerBundlePasscode.normalise("IoL"))
    }

    @Test
    fun `invalid export-code characters are classified before zip decryption`() {
        val error = assertThrows(OpenTagViewerImportException::class.java) {
            fixture().use { OpenTagViewerZipReader.read(it, "not/a/code") }
        }
        assertEquals(OpenTagViewerImportReason.INVALID_PASSCODE_FORMAT, error.reason)
    }

    @Test
    fun `unsafe path is rejected even when entry type is ignored`() {
        val error = assertThrows(OpenTagViewerImportException::class.java) {
            OpenTagViewerZipReader.read(
                zip(
                    "OPENTAGVIEWER.yml" to VALID_MANIFEST_0_0_3,
                    "../ignored.txt" to "ignored",
                    "CustomAccessories/bike-123.json" to CUSTOM_ACCESSORY,
                ),
            )
        }
        assertEquals(OpenTagViewerImportReason.DAMAGED, error.reason)
    }

    @Test
    fun `missing manifest is reported as a different zip`() {
        val error = assertThrows(OpenTagViewerImportException::class.java) {
            OpenTagViewerZipReader.read(zip("CustomAccessories/bike-123.json" to CUSTOM_ACCESSORY))
        }
        assertEquals(OpenTagViewerImportReason.NOT_AN_EXPORT, error.reason)
    }

    @Test
    fun `duplicate recognized entries are rejected`() {
        val original = zipBytes(
            "OPENTAGVIEWER.yml" to VALID_MANIFEST_0_0_3,
            "OPENTAGVIEWER.ymx" to VALID_MANIFEST_0_0_3,
            "CustomAccessories/bike-123.json" to CUSTOM_ACCESSORY,
        )
        replaceAscii(original, "OPENTAGVIEWER.ymx", "OPENTAGVIEWER.yml")

        val error = assertThrows(OpenTagViewerImportException::class.java) {
            OpenTagViewerZipReader.read(ByteArrayInputStream(original))
        }
        assertEquals(OpenTagViewerImportReason.DAMAGED, error.reason)
    }

    @Test
    fun `manifest has a smaller metadata-specific size limit`() {
        val oversizedManifest = VALID_MANIFEST_0_0_3 + "#".repeat(17 * 1024)
        val error = assertThrows(OpenTagViewerImportException::class.java) {
            OpenTagViewerZipReader.read(
                zip(
                    "OPENTAGVIEWER.yml" to oversizedManifest,
                    "CustomAccessories/bike-123.json" to CUSTOM_ACCESSORY,
                ),
            )
        }
        assertEquals(OpenTagViewerImportReason.DAMAGED, error.reason)
    }

    @Test
    fun `decompression limit applies to ignored entries`() {
        val oversized = "x".repeat(OpenTagViewerZipReader.MAX_ENTRY_BYTES + 1)
        val error = assertThrows(OpenTagViewerImportException::class.java) {
            OpenTagViewerZipReader.read(
                zip(
                    "OPENTAGVIEWER.yml" to VALID_MANIFEST_0_0_3,
                    "ignored.txt" to oversized,
                    "CustomAccessories/bike-123.json" to CUSTOM_ACCESSORY,
                ),
            )
        }
        assertEquals(OpenTagViewerImportReason.DAMAGED, error.reason)
        assertTrue(error.message.orEmpty().contains("safe import limit"))
    }

    @Test
    fun `orphaned paired records are rejected rather than silently lost`() {
        val error = assertThrows(OpenTagViewerImportException::class.java) {
            OpenTagViewerZipReader.read(
                zip(
                    "OPENTAGVIEWER.yml" to VALID_MANIFEST_0_0_2,
                    "OwnedBeacons/$BEACON.plist" to "owned",
                ),
            )
        }
        assertEquals(OpenTagViewerImportReason.DAMAGED, error.reason)
    }

    private fun fixture() = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("opentagviewer_locked_bundle_fixture.zip"),
    )

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        return ByteArrayInputStream(zipBytes(*entries))
    }

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            for ((name, content) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun replaceAscii(bytes: ByteArray, from: String, to: String) {
        check(from.length == to.length)
        val needle = from.toByteArray(Charsets.US_ASCII)
        val replacement = to.toByteArray(Charsets.US_ASCII)
        var replacements = 0
        for (offset in 0..bytes.size - needle.size) {
            if (needle.indices.all { bytes[offset + it] == needle[it] }) {
                replacement.copyInto(bytes, offset)
                replacements++
            }
        }
        check(replacements >= 2) { "Both local and central ZIP headers should have been patched" }
    }

    private companion object {
        const val BEACON = "0FB0AEAC-C083-405E-A979-4AA6A73F5C56"
        const val RECORD = "12345678-1234-4ABC-9DEF-1234567890AB"
        const val RECORD_2 = "22345678-1234-4ABC-9DEF-1234567890AB"
        const val VALID_MANIFEST_0_0_2 =
            "version: 0.0.2\nexportTimestamp: 1\nvia: test\nsourceUser: tester\n"
        const val VALID_MANIFEST_0_0_3 =
            "version: 0.0.3\nexportTimestamp: 1\nvia: test\nsourceUser: tester\n"
        const val CUSTOM_ACCESSORY =
            """{"type":"custom_rolling_key_accessory","identifier":"bike-123","name":"Bike","private_keys":["000102030405060708090a0b0c0d0e0f101112131415161718191a1b"]}"""
    }
}
