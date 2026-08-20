package me.kavishdevar.librepods.presentation.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.features.findmy.FindMyDisplayEntry
import me.kavishdevar.librepods.features.findmy.FindMyDisplayItem
import me.kavishdevar.librepods.features.findmy.FindMyDisplaySettings
import me.kavishdevar.librepods.features.findmy.FindMyDisplaySource
import me.kavishdevar.librepods.features.findmy.FindMyDisplayUiState
import me.kavishdevar.librepods.features.findmy.FindMyDisplayViewModel
import me.kavishdevar.librepods.features.findmy.FindMyLocation
import me.kavishdevar.librepods.features.findmy.FindMyNetworkPhase
import me.kavishdevar.librepods.features.findmy.FindMyNetworkUiState
import me.kavishdevar.librepods.features.findmy.FindMyPhase
import me.kavishdevar.librepods.features.findmy.FindMyUiState
import me.kavishdevar.librepods.features.findmy.buildFindMyDisplayItems
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Device-first Find My UI adapted from OpenTagViewer's MIT-licensed map/card/list hierarchy.
 * LibrePods keeps its own Compose/data implementation because its two backends differ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FindMyLibraryList(
    state: FindMyUiState,
    networkState: FindMyNetworkUiState,
    displayState: FindMyDisplayUiState,
    displayViewModel: FindMyDisplayViewModel,
    onRefresh: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onRefreshWebDevice: (String) -> Unit,
    onRefreshNetworkAccessory: (String) -> Unit,
    onOpenSourcesSetup: () -> Unit,
    onSignOut: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val context = LocalContext.current
    val parentHeaderPadding = if (LocalDesignSystem.current == DesignSystem.Apple) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    } else {
        0.dp
    }
    val settings = displayState.settings
    val allItems = buildFindMyDisplayItems(
        webDevices = state.devices,
        networkAccessories = networkState.accessories,
        links = settings.links,
    )
    val bothSourcesAvailable = allItems.any {
        FindMyDisplaySource.APPLE_ACCOUNT in it.sources
    } && allItems.any {
        FindMyDisplaySource.FIND_MY_NETWORK in it.sources
    }
    var showHidden by rememberSaveable(displayState.accountId) {
        mutableStateOf(settings.showHiddenDevices)
    }
    var showList by rememberSaveable { mutableStateOf(allItems.none { it.location != null }) }
    var viewChosenByUser by rememberSaveable { mutableStateOf(false) }
    var viewMenuOpen by rememberSaveable { mutableStateOf(false) }
    var showCombineNotice by rememberSaveable(displayState.accountId) {
        mutableStateOf(settings.showCombineNotice)
    }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var sortByRecent by rememberSaveable(displayState.accountId) {
        mutableStateOf(settings.sortByRecent)
    }
    var sortMenuOpen by rememberSaveable { mutableStateOf(false) }
    var overflowOpen by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSignOutWarning by rememberSaveable { mutableStateOf(false) }
    var selectedItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    var renameItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var groupItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    var linkItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    var linkCandidateKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateGroup by rememberSaveable { mutableStateOf(false) }
    var newGroupName by rememberSaveable { mutableStateOf("") }
    var showManageGroups by rememberSaveable { mutableStateOf(false) }
    var renameGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameGroupText by rememberSaveable { mutableStateOf("") }
    var droppedSourceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var droppedTargetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var droppedGroupName by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4_000)
            onMessageShown()
        }
    }
    LaunchedEffect(displayState.accountId) {
        showHidden = settings.showHiddenDevices
        showCombineNotice = settings.showCombineNotice
        sortByRecent = settings.sortByRecent
    }
    LaunchedEffect(allItems.map { it.key to it.location?.timestampMillis }) {
        if (!viewChosenByUser && allItems.isNotEmpty()) {
            showList = allItems.none { it.location != null }
        }
    }
    val visibleItems = allItems
        .filter { showHidden || entryFor(it, settings).visible }
        .filter {
            val query = search.trim().lowercase()
            query.isBlank() || listOf(
                displayName(it, entryFor(it, settings)),
                it.defaultName,
                it.modelLabel.orEmpty(),
                it.sourceLabel,
                it.serialNumbers.joinToString(" "),
                it.stableIdentifier.orEmpty(),
            ).joinToString(" ").lowercase().contains(query)
        }
        .let { rows ->
            if (sortByRecent) {
                rows.sortedWith(
                    compareByDescending<FindMyDisplayItem> {
                        it.location?.timestampMillis ?: Long.MIN_VALUE
                    }.thenBy { displayName(it, entryFor(it, settings)).lowercase() },
                )
            } else {
                rows.sortedBy { displayName(it, entryFor(it, settings)).lowercase() }
            }
        }

    fun isRefreshing(item: FindMyDisplayItem): Boolean =
        state.phase == FindMyPhase.REFRESHING ||
            networkState.phase == FindMyNetworkPhase.REFRESHING_REPORTS ||
            item.webDevice?.id in state.refreshingDeviceIds ||
            item.networkAccessory?.beaconId in networkState.refreshingBeaconIds

    fun refreshItem(item: FindMyDisplayItem) {
        item.webDevice?.id?.let(onRefreshWebDevice)
        item.networkAccessory?.beaconId?.let(onRefreshNetworkAccessory)
    }

    val itemByKey = allItems.associateBy(FindMyDisplayItem::key)
    val editItem = editItemKey?.let(itemByKey::get)
    val renameItem = renameItemKey?.let(itemByKey::get)
    val groupItem = groupItemKey?.let(itemByKey::get)
    val linkItem = linkItemKey?.let(itemByKey::get)
    val linkCandidate = linkCandidateKey?.let(itemByKey::get)
    val renameGroup = renameGroupId?.let { groupId -> settings.groups.firstOrNull { it.id == groupId } }
    val droppedSource = droppedSourceKey?.let(itemByKey::get)
    val droppedTarget = droppedTargetKey?.let(itemByKey::get)

    renameItem?.let { item ->
        AlertDialog(
            onDismissRequest = { renameItemKey = null },
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(80) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        displayViewModel.rename(item.memberKeys, renameText)
                        renameItemKey = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameItemKey = null }) { Text("Cancel") } },
        )
    }

    renameGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { renameGroupId = null },
            title = { Text("Rename group") },
            text = {
                OutlinedTextField(
                    value = renameGroupText,
                    onValueChange = { renameGroupText = it.take(48) },
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameGroupText.isNotBlank(),
                    onClick = {
                        displayViewModel.renameGroup(group.id, renameGroupText)
                        renameGroupId = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameGroupId = null }) { Text("Cancel") } },
        )
    }

    if (droppedSource != null && droppedTarget != null) {
        AlertDialog(
            onDismissRequest = {
                droppedSourceKey = null
                droppedTargetKey = null
            },
            title = { Text("Create group") },
            text = {
                Column {
                    Text("These devices will be shown together:")
                    Text(
                        "• ${displayName(droppedSource, entryFor(droppedSource, settings))}",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "• ${displayName(droppedTarget, entryFor(droppedTarget, settings))}",
                    )
                    OutlinedTextField(
                        value = droppedGroupName,
                        onValueChange = { droppedGroupName = it.take(48) },
                        label = { Text("Group name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = droppedGroupName.isNotBlank(),
                    onClick = {
                        displayViewModel.createGroup(
                            droppedGroupName,
                            droppedSource.memberKeys + droppedTarget.memberKeys,
                        )
                        droppedSourceKey = null
                        droppedTargetKey = null
                        droppedGroupName = ""
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        droppedSourceKey = null
                        droppedTargetKey = null
                    },
                ) { Text("Cancel") }
            },
        )
    }

    groupItem?.let { item ->
        AlertDialog(
            onDismissRequest = { groupItemKey = null },
            title = { Text("Move to group") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            displayViewModel.assignGroup(item.memberKeys, null)
                            groupItemKey = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("No group", modifier = Modifier.fillMaxWidth()) }
                    settings.groups.forEach { group ->
                        TextButton(
                            onClick = {
                                displayViewModel.assignGroup(item.memberKeys, group.id)
                                groupItemKey = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(group.name, modifier = Modifier.fillMaxWidth()) }
                    }
                    if (settings.groups.isEmpty()) Text("No groups yet.")
                }
            },
            confirmButton = { TextButton(onClick = { groupItemKey = null }) { Text("Cancel") } },
        )
    }

    LaunchedEffect(linkItemKey) {
        linkCandidateKey = null
    }

    linkItem?.let { item ->
        val candidates = allItems.filter { candidate ->
            entryFor(candidate, settings).visible && canLinkDisplayItems(item, candidate)
        }.sortedWith(
            compareByDescending<FindMyDisplayItem> {
                displayName(item, entryFor(item, settings)).normalizedDeviceLabel() ==
                    displayName(it, entryFor(it, settings)).normalizedDeviceLabel()
            }.thenBy { displayName(it, entryFor(it, settings)).lowercase() },
        )
        AlertDialog(
            onDismissRequest = {
                linkItemKey = null
                linkCandidateKey = null
            },
            title = { Text(if (linkCandidate == null) "Combine records" else "Confirm combine") },
            text = {
                Column {
                    if (linkCandidate == null) {
                        Text(
                            "This record is from ${item.sourceLabel}. Choose the record from the other " +
                                "source only if both rows represent the same physical device. " +
                                "The IDs cannot be matched automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        candidates.forEach { candidate ->
                            TextButton(
                                onClick = { linkCandidateKey = candidate.key },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        "Combine with ${displayName(candidate, entryFor(candidate, settings))}",
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "${candidate.sourceLabel} · ${latestReportText(candidate.location)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    identityText(candidate)?.let { identity ->
                                        Text(
                                            identity,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        }
                        if (candidates.isEmpty()) Text("No unlinked record from the other source.")
                    } else {
                        Text(
                            "Combine these two records into one row? This keeps the newest location " +
                                "and preserves each source's report history.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            displayName(item, entryFor(item, settings)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            "${item.sourceLabel} · ${latestReportText(item.location)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        identityText(item)?.let { identity ->
                            Text(
                                identity,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Text(
                            displayName(linkCandidate, entryFor(linkCandidate, settings)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Text(
                            "${linkCandidate.sourceLabel} · ${latestReportText(linkCandidate.location)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        identityText(linkCandidate)?.let { identity ->
                            Text(
                                identity,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (linkCandidate == null) {
                    TextButton(onClick = { linkItemKey = null }) { Text("Cancel") }
                } else {
                    TextButton(
                        onClick = {
                            displayViewModel.link(
                                item.memberKeys.single(),
                                linkCandidate.memberKeys.single(),
                            )
                            linkItemKey = null
                            linkCandidateKey = null
                        },
                    ) { Text("Combine") }
                }
            },
            dismissButton = linkCandidate?.let {
                {
                    TextButton(onClick = { linkCandidateKey = null }) { Text("Back") }
                }
            },
        )
    }

    if (showCreateGroup) {
        AlertDialog(
            onDismissRequest = { showCreateGroup = false },
            title = { Text("Create group") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it.take(48) },
                    label = { Text("Group name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newGroupName.isNotBlank(),
                    onClick = {
                        displayViewModel.createGroup(newGroupName)
                        newGroupName = ""
                        showCreateGroup = false
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateGroup = false }) { Text("Cancel") } },
        )
    }

    if (showManageGroups) {
        AlertDialog(
            onDismissRequest = { showManageGroups = false },
            title = { Text("Groups") },
            text = {
                Column {
                    settings.groups.forEach { group ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(group.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = { displayViewModel.deleteGroup(group.id) }) {
                                Text("Delete")
                            }
                        }
                    }
                    if (settings.groups.isEmpty()) Text("No groups yet.")
                }
            },
            confirmButton = { TextButton(onClick = { showManageGroups = false }) { Text("Done") } },
        )
    }

    editItem?.let { item ->
        val entry = entryFor(item, settings)
        DeviceDetailsDialog(
            item = item,
            entry = entry,
            groupName = settings.groups.firstOrNull { it.id == entry.groupId }?.name,
            refreshing = isRefreshing(item),
            onDismiss = { editItemKey = null },
            onRefresh = { refreshItem(item) },
            onOpenMap = item.location?.let { location ->
                { openLibraryMap(context, displayName(item, entry), location) }
            },
            onRename = {
                renameText = displayName(item, entry)
                renameItemKey = item.key
                editItemKey = null
            },
            onGroup = {
                groupItemKey = item.key
                editItemKey = null
            },
            onToggleVisible = {
                displayViewModel.setVisible(item.key, !entry.visible)
                editItemKey = null
            },
            onLink = {
                if (item.isLinked) displayViewModel.unlink(item.memberKeys)
                else linkItemKey = item.key
                editItemKey = null
            },
        )
    }

    if (overflowOpen) {
        AlertDialog(
            onDismissRequest = { overflowOpen = false },
            title = { Text("Find My options") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            overflowOpen = false
                            onOpenSourcesSetup()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Find My sources setup", Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = {
                            overflowOpen = false
                            showSettings = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Find My settings", Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {
                TextButton(onClick = { overflowOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Find My settings") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    StyledToggle(
                        label = "Show duplicate notice",
                        description = "Show the reminder when Apple-account and network records may be duplicated.",
                        checked = showCombineNotice,
                        onCheckedChange = {
                            showCombineNotice = it
                            displayViewModel.updateLibraryPreferences(showCombineNotice = it)
                        },
                    )
                    StyledToggle(
                        label = "Show removed devices",
                        description = "Include devices you removed from the library.",
                        checked = showHidden,
                        onCheckedChange = {
                            showHidden = it
                            displayViewModel.updateLibraryPreferences(showHiddenDevices = it)
                        },
                    )
                    Text(
                        "Sort by",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Box {
                        TextButton(
                            onClick = { sortMenuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (sortByRecent) "Latest location" else "Name")
                                Text("▾", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Latest location") },
                                onClick = {
                                    sortByRecent = true
                                    sortMenuOpen = false
                                    displayViewModel.updateLibraryPreferences(sortByRecent = true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Name") },
                                onClick = {
                                    sortByRecent = false
                                    sortMenuOpen = false
                                    displayViewModel.updateLibraryPreferences(sortByRecent = false)
                                },
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        "Groups",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(
                        onClick = {
                            showSettings = false
                            showCreateGroup = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Create group", Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = {
                            showSettings = false
                            showManageGroups = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Manage groups", Modifier.fillMaxWidth()) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    TextButton(
                        onClick = {
                            showSettings = false
                            showSignOutWarning = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Sign out of Apple Find My",
                            Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("Done") }
            },
        )
    }

    if (showSignOutWarning) {
        AlertDialog(
            onDismissRequest = { showSignOutWarning = false },
            title = { Text("Sign out of Find My?") },
            text = {
                Text(
                    "This signs out of the Apple-account Find My session. Your names, groups, " +
                        "visibility choices, and stored network session are kept. The separate " +
                        "Find My network account can be managed from Sources setup.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutWarning = false
                        onSignOut()
                    },
                ) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutWarning = false }) { Text("Cancel") }
            },
        )
    }

    val busyAll = state.phase == FindMyPhase.REFRESHING ||
        networkState.phase == FindMyNetworkPhase.REFRESHING_REPORTS
    Scaffold(
        modifier = Modifier.padding(top = parentHeaderPadding),
        topBar = {
            TopAppBar(
                title = {
                    if (searchVisible && showList) {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            placeholder = { Text("Search devices") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).padding(end = 2.dp),
                            ) {
                                Text(
                                    if (showList) "My devices" else "Find My",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${visibleItems.size} device${if (visibleItems.size == 1) "" else "s"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box(
                                modifier = Modifier.widthIn(min = 88.dp, max = 112.dp),
                            ) {
                                TextButton(
                                    onClick = { viewMenuOpen = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(start = 2.dp),
                                ) {
                                    Text(
                                        if (showList) "Devices" else "Map",
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                DropdownMenu(
                                    expanded = viewMenuOpen,
                                    onDismissRequest = { viewMenuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Map") },
                                        onClick = {
                                            showList = false
                                            viewChosenByUser = true
                                            viewMenuOpen = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Devices") },
                                        onClick = {
                                            showList = true
                                            viewChosenByUser = true
                                            viewMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    if (showList) {
                        IconButton(onClick = {
                            if (searchVisible) search = ""
                            searchVisible = !searchVisible
                        }) {
                            Icon(
                                if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = if (searchVisible) "Close search" else "Search",
                            )
                        }
                    }
                    IconButton(
                        enabled = !busyAll,
                        onClick = {
                            if (state.phase == FindMyPhase.READY) onRefresh()
                            if (networkState.accessories.isNotEmpty()) onRefreshNetwork()
                        },
                    ) {
                        if (busyAll) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh all devices")
                        }
                    }
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                },
                // The app-level scaffold already owns system-bar insets.
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxSize().weight(1f)) {
                if (showList) {
                    DeviceList(
                        items = visibleItems,
                        settings = settings,
                        refreshing = ::isRefreshing,
                        onRefresh = ::refreshItem,
                        onOpen = { editItemKey = it.key },
                        onRename = { item ->
                            renameText = displayName(item, entryFor(item, settings))
                            renameItemKey = item.key
                        },
                        onRenameGroup = { groupId, groupName ->
                            renameGroupText = groupName
                            renameGroupId = groupId
                        },
                        hasLinkCandidate = { item ->
                            allItems.any { candidate ->
                                entryFor(candidate, settings).visible &&
                                canLinkDisplayItems(item, candidate) &&
                                    likelySameDisplayItem(item, candidate, settings)
                            }
                        },
                        onDrop = { sourceKey, targetKey ->
                            if (sourceKey != targetKey) {
                                val targetGroupId = itemByKey[targetKey]
                                    ?.let { entryFor(it, settings).groupId }
                                if (targetGroupId != null) {
                                    itemByKey[sourceKey]?.memberKeys?.let { sourceMemberKeys ->
                                        displayViewModel.assignGroup(sourceMemberKeys, targetGroupId)
                                    }
                                } else {
                                    droppedSourceKey = sourceKey
                                    droppedTargetKey = targetKey
                                    droppedGroupName = ""
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    DeviceMap(
                        items = visibleItems.filter { it.location != null },
                        settings = settings,
                        selectedItemKey = selectedItemKey,
                        onSelected = { selectedItemKey = it.key },
                        refreshing = ::isRefreshing,
                        onRefresh = ::refreshItem,
                        onOpen = { editItemKey = it.key },
                        onOpenMap = { item ->
                            item.location?.let {
                                openLibraryMap(context, displayName(item, entryFor(item, settings)), it)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (bothSourcesAvailable && showCombineNotice) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(12.dp)
                            .zIndex(2f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Both sources are shown. The same device may appear twice until " +
                                    "you combine the records.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                showCombineNotice = false
                                displayViewModel.updateLibraryPreferences(showCombineNotice = false)
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss combine notice")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceList(
    items: List<FindMyDisplayItem>,
    settings: FindMyDisplaySettings,
    refreshing: (FindMyDisplayItem) -> Boolean,
    onRefresh: (FindMyDisplayItem) -> Unit,
    onOpen: (FindMyDisplayItem) -> Unit,
    onRename: (FindMyDisplayItem) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    hasLinkCandidate: (FindMyDisplayItem) -> Boolean,
    onDrop: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowBounds = remember { mutableStateMapOf<String, Rect>() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOverKey by remember { mutableStateOf<String?>(null) }
    val assignedKeys = settings.groups.flatMapTo(mutableSetOf()) { group ->
        items.filter { entryFor(it, settings).groupId == group.id }.map(FindMyDisplayItem::key)
    }
    val sections = buildList {
        settings.groups.forEach { group ->
            val groupItems = items.filter { entryFor(it, settings).groupId == group.id }
            if (groupItems.isNotEmpty()) add(DeviceSection(group.id, group.name, groupItems))
        }
        val ungrouped = items.filter { it.key !in assignedKeys }
        if (ungrouped.isNotEmpty()) {
            add(
                DeviceSection(
                    key = UNGROUPED_SECTION_KEY,
                    name = if (settings.groups.isEmpty()) "Devices" else "Ungrouped",
                    items = ungrouped,
                ),
            )
        }
    }
    LazyColumn(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        if (items.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillParentMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        "No devices in this view",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text("Use the options menu to restore hidden devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (items.size > 1) {
            item(key = "grouping-tip") {
                Text(
                    "Tap a device name to rename it · long-press and drag onto another device to group",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        sections.forEach { section ->
            item(key = "section:${section.key}") {
                val canRename = section.key != UNGROUPED_SECTION_KEY
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .then(
                            if (canRename) {
                                Modifier.clickable { onRenameGroup(section.key, section.name) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        section.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (canRename) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Rename ${section.name}",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            items(section.items, key = FindMyDisplayItem::key) { item ->
                val entry = entryFor(item, settings)
                DeviceListRow(
                    item = item,
                    name = displayName(item, entry),
                    refreshing = refreshing(item),
                    onRefresh = { onRefresh(item) },
                    onOpen = { onOpen(item) },
                    onRename = { onRename(item) },
                    hasLinkCandidate = hasLinkCandidate(item),
                    isDragging = draggingKey == item.key,
                    isDropTarget = dragOverKey == item.key,
                    onPositioned = { coordinates -> rowBounds[item.key] = coordinates.boundsInRoot() },
                    onDragStart = {
                        draggingKey = item.key
                        dragOverKey = null
                    },
                    onDragPosition = { localY ->
                        val sourceBounds = rowBounds[item.key]
                        if (sourceBounds != null) {
                            val rootY = sourceBounds.top + localY
                            dragOverKey = rowBounds.entries.firstOrNull { (key, bounds) ->
                                key != item.key && rootY >= bounds.top && rootY <= bounds.bottom
                            }?.key
                        }
                    },
                    onDragEnd = {
                        val targetKey = dragOverKey
                        draggingKey = null
                        dragOverKey = null
                        targetKey?.let { onDrop(item.key, it) }
                    },
                    onDragCancel = {
                        draggingKey = null
                        dragOverKey = null
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DeviceListRow(
    item: FindMyDisplayItem,
    name: String,
    refreshing: Boolean,
    hasLinkCandidate: Boolean,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    isDragging: Boolean,
    isDropTarget: Boolean,
    onPositioned: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
    onDragStart: () -> Unit,
    onDragPosition: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val currentOnDragStart = rememberUpdatedState(onDragStart)
    val currentOnDragPosition = rememberUpdatedState(onDragPosition)
    val currentOnDragEnd = rememberUpdatedState(onDragEnd)
    val currentOnDragCancel = rememberUpdatedState(onDragCancel)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned(onPositioned)
            .pointerInput(item.key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart.value() },
                    onDrag = { change, _ ->
                        change.consume()
                        currentOnDragPosition.value(change.position.y)
                    },
                    onDragEnd = { currentOnDragEnd.value() },
                    onDragCancel = { currentOnDragCancel.value() },
                )
            }
            .clickable(onClick = onOpen)
            .background(
                when {
                    isDropTarget -> MaterialTheme.colorScheme.primaryContainer
                    isDragging -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
            )
            .padding(start = 16.dp, top = 13.dp, end = 8.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceGlyph(name)
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable(onClick = onRename),
                )
                if (item.location == null) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "No known location",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 7.dp).size(18.dp),
                    )
                }
            }
            Text(
                latestReportText(item.location),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (item.isLinked) item.sourceLabel
                else item.locationSource?.label ?: item.sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
            identityText(item)?.let { identity ->
                Text(
                    identity,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasLinkCandidate && !item.isLinked) {
                Text(
                    "Possible duplicate · tap for combine options",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            if (refreshing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh ${name}")
            }
        }
    }
}

@Composable
private fun DeviceMap(
    items: List<FindMyDisplayItem>,
    settings: FindMyDisplaySettings,
    selectedItemKey: String?,
    onSelected: (FindMyDisplayItem) -> Unit,
    refreshing: (FindMyDisplayItem) -> Boolean,
    onRefresh: (FindMyDisplayItem) -> Unit,
    onOpen: (FindMyDisplayItem) -> Unit,
    onOpenMap: (FindMyDisplayItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Box(modifier.background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Place, null, Modifier.size(52.dp), MaterialTheme.colorScheme.outline)
                Text("No known locations", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Refresh a device or switch to Devices above.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val pagerState = rememberPagerState(
        initialPage = items.indexOfFirst { it.key == selectedItemKey }.coerceAtLeast(0),
        pageCount = { items.size },
    )
    val scope = rememberCoroutineScope()
    val itemKeys = items.map(FindMyDisplayItem::key)
    LaunchedEffect(itemKeys, selectedItemKey) {
        val selectedIndex = items.indexOfFirst { it.key == selectedItemKey }
        val targetIndex = selectedIndex.takeIf { it >= 0 } ?: 0
        if (targetIndex != pagerState.currentPage) pagerState.scrollToPage(targetIndex)
        if (selectedItemKey == null) items.getOrNull(targetIndex)?.let(onSelected)
    }
    // Only publish page -> key after an actual swipe settles. List reordering is handled above
    // with the stable selected key as the source of truth.
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            items.getOrNull(pagerState.settledPage)?.let(onSelected)
        }
    }
    Box(modifier) {
        OpenStreetMap(
            items = items,
            settings = settings,
            selectedItemKey = selectedItemKey ?: items.first().key,
            onSelected = { item ->
                onSelected(item)
                val index = items.indexOfFirst { it.key == item.key }
                if (index >= 0) scope.launch { pagerState.animateScrollToPage(index) }
            },
            modifier = Modifier.fillMaxSize(),
        )
        HorizontalPager(
            state = pagerState,
            key = { items[it].key },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        ) { page ->
            val item = items[page]
            OpenTagDeviceCard(
                item = item,
                name = displayName(item, entryFor(item, settings)),
                refreshing = refreshing(item),
                onRefresh = { onRefresh(item) },
                onOpen = { onOpen(item) },
                onOpenMap = { onOpenMap(item) },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun OpenTagDeviceCard(
    item: FindMyDisplayItem,
    name: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(Modifier.padding(top = 15.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeviceGlyph(name, size = 56)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        libraryLocationDescription(item.location),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        latestReportText(item.location),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    identityText(item)?.let { identity ->
                        Text(
                            identity,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RoundCardAction(Icons.Default.Place, "Directions", false, onOpenMap)
                RoundCardAction(Icons.Default.Refresh, "Refresh", refreshing, onRefresh)
                RoundCardAction(Icons.Default.MoreVert, "More", false, onOpen)
            }
        }
    }
}

@Composable
private fun RoundCardAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(44.dp).clickable(enabled = !loading, onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(icon, contentDescription = label, modifier = Modifier.size(23.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DeviceDetailsDialog(
    item: FindMyDisplayItem,
    entry: FindMyDisplayEntry,
    groupName: String?,
    refreshing: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onOpenMap: (() -> Unit)?,
    onRename: () -> Unit,
    onGroup: () -> Unit,
    onToggleVisible: () -> Unit,
    onLink: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceGlyph(displayName(item, entry))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(displayName(item, entry), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        item.modelLabel ?: item.sourceLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    identityText(item)?.let { identity ->
                        Text(
                            identity,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        text = {
            Column {
                Text("Latest report", fontWeight = FontWeight.SemiBold)
                Text(libraryLocationDescription(item.location))
                Text(
                    "${if (item.isLinked) item.sourceLabel else item.locationSource?.label ?: item.sourceLabel} · " +
                        latestReportText(item.location),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.location?.horizontalAccuracyMeters?.let {
                    Text(
                        "Accuracy ±${it.roundToInt()} m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                groupName?.let {
                    Text("Group: ${it}", modifier = Modifier.padding(top = 6.dp))
                }
                if (item.isLinked) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    item.webDevice?.location?.let {
                        SourceLocationLine(FindMyDisplaySource.APPLE_ACCOUNT, it)
                    }
                    item.networkAccessory?.location?.let {
                        SourceLocationLine(FindMyDisplaySource.FIND_MY_NETWORK, it)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RoundCardAction(Icons.Default.Refresh, "Refresh", refreshing, onRefresh)
                    onOpenMap?.let { openMap ->
                        RoundCardAction(Icons.Default.Place, "Map", false, openMap)
                    }
                    RoundCardAction(Icons.Default.Edit, "Rename", false, onRename)
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                TextButton(onClick = onGroup, modifier = Modifier.fillMaxWidth()) {
                    Text("Move to group", modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onLink, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (item.isLinked) "Separate Apple and network records"
                        else "Combine with another source",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = onToggleVisible, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (entry.visible) "Remove from view" else "Restore to view",
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun OpenStreetMap(
    items: List<FindMyDisplayItem>,
    settings: FindMyDisplaySettings,
    selectedItemKey: String,
    onSelected: (FindMyDisplayItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val selected = MaterialTheme.colorScheme.tertiary.toArgb()
    val map = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlays.add(CopyrightOverlay(context))
        }
    }
    var fittedSignature by remember { mutableStateOf<String?>(null) }
    DisposableEffect(map, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            map.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            map.onPause()
            map.onDetach()
        }
    }
    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { mapView ->
            mapView.overlays.removeAll { it is Marker }
            items.forEach { item ->
                val location = item.location ?: return@forEach
                Marker(mapView).also { marker ->
                    marker.position = GeoPoint(location.latitude, location.longitude)
                    marker.title = displayName(item, entryFor(item, settings))
                    marker.snippet = latestReportText(location)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    marker.icon = markerDrawable(
                        context,
                        marker.title,
                        if (item.key == selectedItemKey) selected else primary,
                    )
                    marker.setOnMarkerClickListener { _, _ ->
                        onSelected(item)
                        mapView.controller.animateTo(marker.position)
                        true
                    }
                    mapView.overlays.add(marker)
                }
            }
            val signature = items.sortedBy(FindMyDisplayItem::key).joinToString("|") {
                "${it.key}:${it.location?.latitude}:${it.location?.longitude}"
            }
            if (fittedSignature != signature) {
                fittedSignature = signature
                val points = items.mapNotNull { it.location }.map { GeoPoint(it.latitude, it.longitude) }
                mapView.post {
                    if (points.size == 1) {
                        mapView.controller.setZoom(15.5)
                        mapView.controller.setCenter(points.single())
                    } else if (points.isNotEmpty()) {
                        mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, 100)
                    }
                }
            }
            mapView.invalidate()
        },
    )
    // Do not animate from AndroidView.update: map gestures can trigger view updates and that
    // would fight the user's pan/zoom. Recenter only when the selected device actually changes.
    LaunchedEffect(selectedItemKey) {
        items.firstOrNull { it.key == selectedItemKey }?.location?.let { location ->
            map.post { map.controller.animateTo(GeoPoint(location.latitude, location.longitude)) }
        }
    }
}

private fun markerDrawable(context: Context, label: String, color: Int): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val size = (46 * density).roundToInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.White.toArgb()
    canvas.drawCircle(size / 2f, size / 2f, size * 0.48f, paint)
    paint.color = color
    canvas.drawCircle(size / 2f, size / 2f, size * 0.39f, paint)
    paint.color = Color.White.toArgb()
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    paint.textSize = size * 0.42f
    val initial = deviceGlyph(label)
    val baseline = size / 2f - (paint.ascent() + paint.descent()) / 2f
    canvas.drawText(initial, size / 2f, baseline, paint)
    return BitmapDrawable(context.resources, bitmap)
}

@Composable
private fun DeviceGlyph(name: String, size: Int = 44) {
    Surface(
        modifier = Modifier.size(size.dp).clip(CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                deviceGlyph(name),
                style = if (size > 50) MaterialTheme.typography.headlineSmall
                else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SourceLocationLine(source: FindMyDisplaySource, location: FindMyLocation) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(source.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Text(
            latestReportText(location),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun entryFor(item: FindMyDisplayItem, settings: FindMyDisplaySettings): FindMyDisplayEntry =
    settings.entries[item.key]
        ?: item.memberKeys.asSequence().sorted().mapNotNull(settings.entries::get).firstOrNull()
        ?: FindMyDisplayEntry()

private fun displayName(item: FindMyDisplayItem, entry: FindMyDisplayEntry): String =
    entry.customName?.takeIf(String::isNotBlank) ?: item.defaultName

private fun identityText(item: FindMyDisplayItem): String? = when {
    item.serialNumbers.isNotEmpty() -> item.serialNumbers.joinToString(" · ") { serial ->
        "Serial $serial"
    }

    !item.stableIdentifier.isNullOrBlank() -> {
        val identifier = item.stableIdentifier.orEmpty()
        "Identifier …${identifier.takeLast(8)}"
    }

    else -> null
}

private fun canLinkDisplayItems(
    item: FindMyDisplayItem,
    candidate: FindMyDisplayItem,
): Boolean = !item.isLinked && !candidate.isLinked && item.key != candidate.key &&
    item.memberKeys.size == 1 && candidate.memberKeys.size == 1 &&
    ((item.webDevice != null && item.networkAccessory == null && candidate.networkAccessory != null) ||
        (item.networkAccessory != null && item.webDevice == null && candidate.webDevice != null))

private fun likelySameDisplayItem(
    item: FindMyDisplayItem,
    candidate: FindMyDisplayItem,
    settings: FindMyDisplaySettings,
): Boolean {
    val leftLabels = buildSet {
        add(displayName(item, entryFor(item, settings)).normalizedDeviceLabel())
        item.modelLabel?.normalizedDeviceLabel()?.let(::add)
    }.filter { it.length >= 8 }
    val rightLabels = buildSet {
        add(displayName(candidate, entryFor(candidate, settings)).normalizedDeviceLabel())
        candidate.modelLabel?.normalizedDeviceLabel()?.let(::add)
    }.filter { it.length >= 8 }
    return leftLabels.any { left ->
        rightLabels.any { right ->
            left == right || (left.length >= 10 && right.length >= 10 &&
                (left.contains(right) || right.contains(left)))
        }
    }
}

private fun String.normalizedDeviceLabel(): String = lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]"), "")

private fun latestReportText(location: FindMyLocation?): String = when {
    location == null -> "No location is known"
    location.timestampMillis == null -> "Latest report time unavailable"
    else -> "Updated ${libraryRelativeTime(location.timestampMillis)}"
}

private fun libraryLocationDescription(location: FindMyLocation?): String = when {
    location == null -> "No location available"
    else -> "${formatLibraryCoordinate(location.latitude)}, " +
        formatLibraryCoordinate(location.longitude)
}

private fun libraryRelativeTime(timestampMillis: Long): String = DateUtils.getRelativeTimeSpanString(
    timestampMillis,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
).toString()

private fun formatLibraryCoordinate(value: Double): String =
    String.format(Locale.US, "%.5f", value)

private fun deviceGlyph(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return "•"
    val codePoint = trimmed.codePointAt(0)
    return String(Character.toChars(codePoint)).uppercase(Locale.getDefault())
}

private data class DeviceSection(
    val key: String,
    val name: String,
    val items: List<FindMyDisplayItem>,
)

private const val UNGROUPED_SECTION_KEY = "__librepods_ungrouped__"

private fun openLibraryMap(context: Context, label: String, location: FindMyLocation) {
    val encoded = Uri.encode(label)
    val geo = Uri.parse(
        "geo:${location.latitude},${location.longitude}" +
            "?q=${location.latitude},${location.longitude}(${encoded})",
    )
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, geo))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "https://www.openstreetmap.org/?mlat=${location.latitude}" +
                            "&mlon=${location.longitude}#map=17/${location.latitude}/${location.longitude}",
                    ),
                ),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No maps app or browser is available.", Toast.LENGTH_LONG).show()
        }
    }
}
