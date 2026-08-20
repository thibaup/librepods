package me.kavishdevar.librepods.features.findmy

/** The two Apple data planes shown in the Find My library. */
enum class FindMyDisplaySource(val label: String) {
    APPLE_ACCOUNT("Apple account"),
    FIND_MY_NETWORK("Find My network"),
}

internal data class FindMyDisplayGroup(
    val id: String,
    val name: String,
    val sortOrder: Int,
)

internal data class FindMyDisplayEntry(
    val customName: String? = null,
    val visible: Boolean = true,
    val groupId: String? = null,
)

internal data class FindMyDisplaySettings(
    val groups: List<FindMyDisplayGroup> = emptyList(),
    val entries: Map<String, FindMyDisplayEntry> = emptyMap(),
    /** Explicit links only. We never merge records merely because their labels look alike. */
    val links: Map<String, String> = emptyMap(),
    /** Library presentation preferences are stored with the account's display profile. */
    val showCombineNotice: Boolean = true,
    val showHiddenDevices: Boolean = false,
    val sortByRecent: Boolean = true,
)

/**
 * A display row built from one or both backends.
 *
 * FMIP's device id and Find My network's beacon id are different namespaces. Rows are kept
 * separate unless the user explicitly links them. This prevents two similarly-named AirPods from
 * silently becoming one; the current code does not establish that FMIP ids and beacon ids share
 * a namespace, even when their text happens to match.
 */
internal data class FindMyDisplayItem(
    val key: String,
    val memberKeys: Set<String>,
    val webDevice: FindMyDevice? = null,
    val networkAccessory: FindMyNetworkAccessory? = null,
    val defaultName: String,
    val location: FindMyLocation?,
    val locationSource: FindMyDisplaySource?,
    val sources: Set<FindMyDisplaySource>,
    val reportCount: Int,
) {
    val isLinked: Boolean get() = memberKeys.size > 1
    val sourceLabel: String get() = when {
        sources.size > 1 -> "Apple account + Find My network"
        sources.singleOrNull() != null -> sources.single().label
        else -> "Find My"
    }

    val modelLabel: String?
        get() = listOfNotNull(
            webDevice?.modelName,
            webDevice?.displayName,
            networkAccessory?.hardwareLabel,
        ).distinct().joinToString(" · ").takeIf(String::isNotBlank)

    /** All serials available for this row, retaining both values on an explicitly linked row. */
    val serialNumbers: List<String>
        get() = listOfNotNull(
            webDevice?.serialNumber,
            networkAccessory?.serialNumber,
        ).map(String::trim).filter(String::isNotBlank).distinct()

    /** The first serial is convenient for callers that only have room for one identity value. */
    val serialNumber: String?
        get() = serialNumbers.firstOrNull()

    /** Namespace-specific fallback when Apple did not expose a serial number. */
    val stableIdentifier: String?
        get() = webDevice?.id?.takeIf(String::isNotBlank)
            ?: networkAccessory?.beaconId?.takeIf(String::isNotBlank)
}

internal fun findMyWebKey(id: String): String = "web:${id.trim()}"

internal fun findMyNetworkKey(beaconId: String): String = "network:${beaconId.trim()}"

internal fun findMyLinkKey(left: String, right: String): String =
    "link:" + listOf(left, right).sorted().joinToString("|")

/**
 * Build a single library without pretending that the two Apple APIs expose the same identity.
 * A link must be made explicitly by the user. The two backends expose different identifiers and
 * the supplied code does not prove that those identifiers can be compared.
 */
internal fun buildFindMyDisplayItems(
    webDevices: List<FindMyDevice>,
    networkAccessories: List<FindMyNetworkAccessory>,
    links: Map<String, String> = emptyMap(),
): List<FindMyDisplayItem> {
    // Apple may return the same stable id more than once. Never let response order decide which
    // duplicate the user sees: retain the record with the newest location timestamp.
    val webRows = webDevices
        .filter { it.id.isNotBlank() }
        .groupBy { findMyWebKey(it.id) }
        .mapValues { (_, candidates) ->
            newestByLocation(candidates, FindMyDevice::location) { device ->
                listOf(
                    device.name,
                    device.displayName,
                    device.modelName.orEmpty(),
                    device.rawModel.orEmpty(),
                    device.location.locationTieBreaker(),
                ).joinToString("\u0000")
            }
        }
    val networkRows = networkAccessories
        .filter { it.beaconId.isNotBlank() }
        .groupBy { findMyNetworkKey(it.beaconId) }
        .mapValues { (_, candidates) ->
            newestByLocation(candidates, FindMyNetworkAccessory::location) { accessory ->
                listOf(
                    accessory.label,
                    accessory.hardwareLabel.orEmpty(),
                    accessory.location.locationTieBreaker(),
                ).joinToString("\u0000")
            }
        }

    val rows = linkedMapOf<String, FindMyDisplayItem>()
    webRows.forEach { (key, device) ->
        rows[key] = displayItem(key, setOf(key), device, null)
    }
    networkRows.forEach { (key, accessory) ->
        rows[key] = displayItem(key, setOf(key), null, accessory)
    }

    // Links are intentionally one-hop. A malformed/stale preference is ignored rather than
    // hiding a device, and a link is only accepted when both endpoints currently exist.
    val consumed = mutableSetOf<String>()
    val symmetric = links.entries.flatMap { (left, right) ->
        listOf(left to right, right to left)
    }.toMap()
    rows.keys.sorted().forEach { key ->
        if (key in consumed) return@forEach
        val partner = symmetric[key]
        if (partner == null || partner !in rows || partner in consumed ||
            rows[key]?.webDevice != null && rows[partner]?.networkAccessory == null ||
            rows[key]?.networkAccessory != null && rows[partner]?.webDevice == null
        ) return@forEach
        val left = rows.getValue(key)
        val right = rows.getValue(partner)
        val memberKeys = left.memberKeys + right.memberKeys
        val mergedKey = findMyLinkKey(key, partner)
        rows[mergedKey] = displayItem(
            key = mergedKey,
            memberKeys = memberKeys,
            webDevice = left.webDevice ?: right.webDevice,
            networkAccessory = left.networkAccessory ?: right.networkAccessory,
        )
        rows.remove(key)
        rows.remove(partner)
        consumed += memberKeys
    }

    return rows.values.sortedWith(
        compareBy<FindMyDisplayItem> { it.defaultName.lowercase() }
            .thenBy { it.key },
    )
}

private fun <T> newestByLocation(
    candidates: List<T>,
    location: (T) -> FindMyLocation?,
    tieBreaker: (T) -> String,
): T = candidates.maxWithOrNull(
        compareBy<T> { location(it) != null }
            .thenBy { location(it)?.timestampMillis != null }
            .thenBy { location(it)?.timestampMillis ?: Long.MIN_VALUE }
            .thenBy(tieBreaker),
    ) ?: error("Cannot choose a newest item from an empty list")

private fun FindMyLocation?.locationTieBreaker(): String = this?.let { location ->
    listOf(
        location.latitude.toString(),
        location.longitude.toString(),
        location.horizontalAccuracyMeters?.toString().orEmpty(),
        location.positionType.orEmpty(),
    ).joinToString("|")
}.orEmpty()

private fun displayItem(
    key: String,
    memberKeys: Set<String>,
    webDevice: FindMyDevice?,
    networkAccessory: FindMyNetworkAccessory?,
): FindMyDisplayItem {
    val webLocation = webDevice?.location
    val networkLocation = networkAccessory?.location
    val locationPair = listOfNotNull(
        webLocation?.let { FindMyDisplaySource.APPLE_ACCOUNT to it },
        networkLocation?.let { FindMyDisplaySource.FIND_MY_NETWORK to it },
    ).maxWithOrNull(compareBy<Pair<FindMyDisplaySource, FindMyLocation>> {
        it.second.timestampMillis ?: Long.MIN_VALUE
    })
    return FindMyDisplayItem(
        key = key,
        memberKeys = memberKeys,
        webDevice = webDevice,
        networkAccessory = networkAccessory,
        defaultName = webDevice?.name?.takeIf(String::isNotBlank)
            ?: networkAccessory?.label?.takeIf(String::isNotBlank)
            ?: "Unnamed device",
        location = locationPair?.second,
        locationSource = locationPair?.first,
        sources = buildSet {
            if (webDevice != null) add(FindMyDisplaySource.APPLE_ACCOUNT)
            if (networkAccessory != null) add(FindMyDisplaySource.FIND_MY_NETWORK)
        },
        reportCount = networkAccessory?.reportCount ?: 0,
    )
}
