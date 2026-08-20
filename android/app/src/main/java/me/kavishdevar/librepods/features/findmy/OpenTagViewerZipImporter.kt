package me.kavishdevar.librepods.features.findmy

import android.content.Context
import android.net.Uri
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.inputstream.ZipInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

internal enum class OpenTagViewerImportReason {
    NOT_A_ZIP,
    NOT_AN_EXPORT,
    DAMAGED,
    LOCKED,
    WRONG_PASSCODE,
    INVALID_PASSCODE_FORMAT,
    NO_TAGS,
    UNREADABLE,
}

internal class OpenTagViewerImportException(
    val reason: OpenTagViewerImportReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal data class OpenTagViewerPairedAccessory(
    val beaconId: String,
    val ownedBeaconPlist: String,
    val namingRecords: List<OpenTagViewerPlistRecord>,
    val keyAlignmentRecords: List<OpenTagViewerPlistRecord>,
)

internal data class OpenTagViewerPlistRecord(
    val recordId: String,
    val plist: String,
)

internal data class OpenTagViewerCustomAccessory(
    val identifier: String,
    val accessoryJson: String,
)

internal data class OpenTagViewerArchive(
    val manifestYaml: String,
    val pairedAccessories: List<OpenTagViewerPairedAccessory>,
    val customAccessories: List<OpenTagViewerCustomAccessory>,
)

/**
 * Reads an OpenTagViewer export without copying it into app storage.
 *
 * Every entry is streamed and bounded, including ignored entries. Owner keys only live in the
 * returned in-memory object until [SecureFindMyNetworkStore] encrypts the converted records.
 */
internal class OpenTagViewerZipImporter(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun import(uri: Uri, passcode: String? = null): OpenTagViewerArchive {
        val source = try {
            resolver.openInputStream(uri)
                ?: throw OpenTagViewerImportException(
                    OpenTagViewerImportReason.UNREADABLE,
                    "The selected export could not be opened.",
                )
        } catch (error: OpenTagViewerImportException) {
            throw error
        } catch (error: IOException) {
            throw OpenTagViewerImportException(
                OpenTagViewerImportReason.UNREADABLE,
                "The selected export could not be read.",
                error,
            )
        } catch (error: SecurityException) {
            throw OpenTagViewerImportException(
                OpenTagViewerImportReason.UNREADABLE,
                "Permission to read the selected export was lost. Choose it again.",
                error,
            )
        }

        return source.use { OpenTagViewerZipReader.read(it, passcode) }
    }
}

internal object OpenTagViewerBundlePasscode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val SEPARATORS = " -_\t\r\n"

    /** Must remain byte-for-byte compatible with opentagviewer_export.passcode. */
    fun normalise(typed: String): String {
        val cleaned = buildString(typed.length) {
            for (raw in typed.uppercase(Locale.ROOT)) {
                if (raw in SEPARATORS) continue
                append(
                    when (raw) {
                        'O' -> '0'
                        'I', 'L' -> '1'
                        else -> raw
                    },
                )
            }
        }
        if (cleaned.isEmpty() || cleaned.any { it !in ALPHABET }) {
            throw OpenTagViewerImportException(
                OpenTagViewerImportReason.INVALID_PASSCODE_FORMAT,
                "The export code contains characters which are not part of an OpenTagViewer code.",
            )
        }
        return cleaned
    }
}

internal object OpenTagViewerZipReader {
    internal const val MAX_ENTRIES = 1_024
    internal const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
    internal const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 16 * 1024

    private val zipSignatures = setOf(
        listOf(0x50, 0x4B, 0x03, 0x04),
        listOf(0x50, 0x4B, 0x05, 0x06),
        listOf(0x50, 0x4B, 0x07, 0x08),
    )
    private const val UUID =
        "([0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12})"
    private val ownedPattern = Regex("^OwnedBeacons/$UUID\\.plist$")
    private val namingPattern = Regex("^BeaconNamingRecord/$UUID/$UUID\\.plist$")
    private val alignmentPattern = Regex("^KeyAlignmentRecords/$UUID/$UUID\\.plist$")
    private val customPattern = Regex("^CustomAccessories/([A-Za-z0-9._-]{1,100})\\.json$")

    fun read(source: InputStream, passcode: String? = null): OpenTagViewerArchive {
        val normalizedPasscode = passcode?.let(OpenTagViewerBundlePasscode::normalise)
        val buffered = if (source is BufferedInputStream) source else BufferedInputStream(source)
        verifyZipSignature(buffered)

        var manifest: String? = null
        val owned = linkedMapOf<String, String>()
        val naming = linkedMapOf<String, MutableMap<String, String>>()
        val alignments = linkedMapOf<String, MutableMap<String, String>>()
        val custom = linkedMapOf<String, String>()
        val namesSeen = hashSetOf<String>()
        var entriesSeen = 0
        var totalBytes = 0
        val passwordChars = normalizedPasscode?.toCharArray()

        try {
            val input = if (normalizedPasscode == null) {
                ZipInputStream(buffered, StandardCharsets.UTF_8)
            } else {
                ZipInputStream(buffered, passwordChars, StandardCharsets.UTF_8)
            }
            input.use { zip ->
                while (true) {
                    val header = zip.nextEntry ?: break
                    entriesSeen++
                    if (entriesSeen > MAX_ENTRIES) {
                        throw damaged("The export contains too many entries.")
                    }

                    val name = header.fileName ?: throw damaged("The export has an unnamed entry.")
                    validateEntryName(name)
                    if (!namesSeen.add(name)) {
                        throw damaged("The export contains duplicate entries.")
                    }
                    if (header.isEncrypted && normalizedPasscode == null) {
                        throw OpenTagViewerImportException(
                            OpenTagViewerImportReason.LOCKED,
                            "This OpenTagViewer export is locked.",
                        )
                    }

                    val match = classify(name)
                    val entryLimit = if (match?.kind == EntryKind.MANIFEST) {
                        MAX_MANIFEST_BYTES
                    } else {
                        MAX_ENTRY_BYTES
                    }
                    val output = if (!header.isDirectory && match != null) {
                        ByteArrayOutputStream()
                    } else {
                        null
                    }
                    var entryBytes = 0
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        entryBytes += count
                        totalBytes += count
                        if (entryBytes > entryLimit || totalBytes > MAX_TOTAL_BYTES) {
                            throw damaged("The export expands beyond the safe import limit.")
                        }
                        output?.write(buffer, 0, count)
                    }
                    if (header.isDirectory || match == null) continue

                    val content = decodeUtf8(output!!.toByteArray())
                    when (match.kind) {
                        EntryKind.MANIFEST -> manifest = content
                        EntryKind.OWNED -> owned[match.primaryId!!] = content
                        EntryKind.NAMING -> naming.getOrPut(match.primaryId!!) { linkedMapOf() }[
                            match.secondaryId!!
                        ] = content
                        EntryKind.ALIGNMENT -> alignments.getOrPut(match.primaryId!!) {
                            linkedMapOf()
                        }[match.secondaryId!!] = content
                        EntryKind.CUSTOM -> custom[match.primaryId!!] = content
                    }
                }
            }
        } catch (error: OpenTagViewerImportException) {
            throw error
        } catch (error: ZipException) {
            if (error.type == ZipException.Type.WRONG_PASSWORD) {
                throw OpenTagViewerImportException(
                    if (normalizedPasscode == null) {
                        OpenTagViewerImportReason.LOCKED
                    } else {
                        OpenTagViewerImportReason.WRONG_PASSCODE
                    },
                    if (normalizedPasscode == null) {
                        "This OpenTagViewer export is locked."
                    } else {
                        "That code did not open the OpenTagViewer export."
                    },
                    error,
                )
            }
            throw damaged("The OpenTagViewer export is damaged or unsupported.", error)
        } catch (error: IOException) {
            throw damaged("The OpenTagViewer export ended unexpectedly.", error)
        } finally {
            passwordChars?.fill('\u0000')
        }

        val manifestYaml = manifest ?: throw OpenTagViewerImportException(
            OpenTagViewerImportReason.NOT_AN_EXPORT,
            "That ZIP is not an OpenTagViewer export.",
        )
        if (manifestYaml.isBlank()) throw damaged("The OpenTagViewer manifest is empty.")

        val paired = owned.entries.map { (beaconId, ownedPlist) ->
            val namingRecords = naming[beaconId]
                ?.toSortedMap()
                ?.map { (recordId, plist) -> OpenTagViewerPlistRecord(recordId, plist) }
                ?.takeIf { it.isNotEmpty() }
                ?: throw damaged("An accessory record is missing its naming record.")
            val alignmentRecords = alignments[beaconId]
                ?.toSortedMap()
                ?.map { (recordId, plist) -> OpenTagViewerPlistRecord(recordId, plist) }
                .orEmpty()
            OpenTagViewerPairedAccessory(beaconId, ownedPlist, namingRecords, alignmentRecords)
        }
        if (naming.keys.any { it !in owned } || alignments.keys.any { it !in owned }) {
            throw damaged("The export contains accessory records which do not match.")
        }
        if (custom.keys.any { it in owned }) {
            throw damaged("The export contains duplicate accessory identifiers.")
        }
        if (paired.isEmpty() && custom.isEmpty()) {
            throw OpenTagViewerImportException(
                OpenTagViewerImportReason.NO_TAGS,
                "The OpenTagViewer export does not contain any accessories.",
            )
        }

        return OpenTagViewerArchive(
            manifestYaml = manifestYaml,
            pairedAccessories = paired,
            customAccessories = custom.map { (identifier, json) ->
                OpenTagViewerCustomAccessory(identifier, json)
            },
        )
    }

    private fun verifyZipSignature(source: BufferedInputStream) {
        source.mark(4)
        val bytes = ByteArray(4)
        var read = 0
        try {
            while (read < bytes.size) {
                val count = source.read(bytes, read, bytes.size - read)
                if (count < 0) break
                read += count
            }
            source.reset()
        } catch (error: IOException) {
            throw OpenTagViewerImportException(
                OpenTagViewerImportReason.UNREADABLE,
                "The selected file could not be read.",
                error,
            )
        }
        val signature = bytes.map { it.toInt() and 0xff }
        if (read != bytes.size || signature !in zipSignatures) {
            throw OpenTagViewerImportException(
                OpenTagViewerImportReason.NOT_A_ZIP,
                "The selected file is not a ZIP archive.",
            )
        }
    }

    private fun validateEntryName(name: String) {
        val parts = name.split('/')
        if (name.isEmpty() || name.length > 512 || '\u0000' in name || '\\' in name ||
            name.startsWith('/') || name.contains("//") ||
            Regex("^[A-Za-z]:").containsMatchIn(name) ||
            parts.any { it == "." || it == ".." }
        ) {
            throw damaged("The export contains an unsafe entry path.")
        }
    }

    private fun classify(name: String): EntryMatch? {
        if (name == "OPENTAGVIEWER.yml") return EntryMatch(EntryKind.MANIFEST)
        ownedPattern.matchEntire(name)?.let {
            return EntryMatch(EntryKind.OWNED, it.groupValues[1])
        }
        namingPattern.matchEntire(name)?.let {
            return EntryMatch(EntryKind.NAMING, it.groupValues[1], it.groupValues[2])
        }
        alignmentPattern.matchEntire(name)?.let {
            return EntryMatch(EntryKind.ALIGNMENT, it.groupValues[1], it.groupValues[2])
        }
        customPattern.matchEntire(name)?.let {
            return EntryMatch(EntryKind.CUSTOM, it.groupValues[1])
        }
        return null
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw damaged("An export entry is not valid UTF-8 text.", error)
    }

    private fun damaged(message: String, cause: Throwable? = null) =
        OpenTagViewerImportException(OpenTagViewerImportReason.DAMAGED, message, cause)

    private enum class EntryKind { MANIFEST, OWNED, NAMING, ALIGNMENT, CUSTOM }

    private data class EntryMatch(
        val kind: EntryKind,
        val primaryId: String? = null,
        val secondaryId: String? = null,
    )
}
