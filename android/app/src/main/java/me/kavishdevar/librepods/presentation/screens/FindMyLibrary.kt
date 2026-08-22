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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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
import me.kavishdevar.librepods.presentation.components.MaterialButtonStyle
import me.kavishdevar.librepods.presentation.components.StyledBottomSheet
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledFloatingSurface
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
import org.osmdroid.views.overlay.TilesOverlay
import java.util.Locale
import kotlin.math.roundToInt

private enum class FindMyViewMode { Devices, Map }
private enum class FindMyManagementTab { Sources, Settings }

/**
 * Device-first Find My library. Behavior and data contracts stay shared; the supplied adaptive
 * floating components own Apple-vs-Material visual treatment.
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
    val chromeTopPadding = if (LocalDesignSystem.current == DesignSystem.Apple) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    } else {
        0.dp
    }
    val backdrop = rememberLayerBackdrop()
    val settings = displayState.settings
    val allItems = buildFindMyDisplayItems(
        webDevices = state.devices,
        networkAccessories = networkState.accessories,
        links = settings.links,
    )
    val showHidden = settings.showHiddenDevices
    val sortByRecent = settings.sortByRecent
    val showCombineNotice = settings.showCombineNotice

    val visibleLibraryItems = allItems
        .filter { showHidden || entryFor(it, settings).visible }
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
    val hasLocatedVisibleDevice = visibleLibraryItems.any { it.location != null }
    var viewMode by rememberSaveable {
        mutableStateOf(if (hasLocatedVisibleDevice) FindMyViewMode.Map else FindMyViewMode.Devices)
    }
    var viewChosenByUser by rememberSaveable { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var managementVisible by rememberSaveable { mutableStateOf(false) }
    var managementTab by rememberSaveable { mutableStateOf(FindMyManagementTab.Sources) }
    var showSignOutWarning by rememberSaveable { mutableStateOf(false) }
    var selectedItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    var viewportLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var viewportLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var viewportZoom by rememberSaveable { mutableStateOf<Double?>(null) }

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
    LaunchedEffect(hasLocatedVisibleDevice) {
        if (!viewChosenByUser) {
            viewMode = if (hasLocatedVisibleDevice) FindMyViewMode.Map else FindMyViewMode.Devices
        }
    }

    val query = search.trim().lowercase(Locale.ROOT)
    val listItems = visibleLibraryItems.filter { item ->
        query.isBlank() || listOf(
            displayName(item, entryFor(item, settings)),
            item.defaultName,
            item.modelLabel.orEmpty(),
            item.sourceLabel,
            item.serialNumbers.joinToString(" "),
            item.stableIdentifier.orEmpty(),
        ).joinToString(" ").lowercase(Locale.ROOT).contains(query)
    }
    val locatedItems = visibleLibraryItems.filter { it.location != null }
    val locatedKeys = locatedItems.map(FindMyDisplayItem::key)
    LaunchedEffect(locatedKeys, selectedItemKey) {
        val normalized = FindMyMapUiPolicy.normalizeSelectedKey(selectedItemKey, locatedKeys)
        if (normalized != selectedItemKey) selectedItemKey = normalized
    }

    val restoredViewport = if (
        viewportLatitude != null && viewportLongitude != null && viewportZoom != null
    ) {
        FindMyMapUiPolicy.restoreViewport(
            listOf(viewportLatitude!!, viewportLongitude!!, viewportZoom!!),
        )
    } else {
        null
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
            backdrop = backdrop,
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

    StyledBottomSheet(
        visible = managementVisible,
        onDismiss = { managementVisible = false },
        backdrop = backdrop,
        skipPartiallyExpanded = true,
        gesturesEnabled = false,
    ) { innerBackdrop, _ ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 560.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Text(
                "Manage Find My",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                SegmentedButton(
                    selected = managementTab == FindMyManagementTab.Sources,
                    onClick = { managementTab = FindMyManagementTab.Sources },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.weight(1f),
                ) { Text("Sources") }
                SegmentedButton(
                    selected = managementTab == FindMyManagementTab.Settings,
                    onClick = { managementTab = FindMyManagementTab.Settings },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.weight(1f),
                ) { Text("Settings") }
            }

            when (managementTab) {
                FindMyManagementTab.Sources -> {
                    SourceStatusRow(
                        title = "Apple account",
                        status = appleLibraryStatus(state),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    SourceStatusRow(
                        title = "Find My network",
                        status = networkLibraryStatus(networkState),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    StyledButton(
                        onClick = {
                            managementVisible = false
                            onOpenSourcesSetup()
                        },
                        backdrop = innerBackdrop,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        Text("Manage sources")
                    }
                }

                FindMyManagementTab.Settings -> {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StyledToggle(
                            label = "Show duplicate notice",
                            description = "Remind me that records from the two sources can represent the same device.",
                            checked = showCombineNotice,
                            onCheckedChange = {
                                displayViewModel.updateLibraryPreferences(showCombineNotice = it)
                            },
                        )
                        StyledToggle(
                            label = "Show removed devices",
                            description = "Include devices previously removed from the library view.",
                            checked = showHidden,
                            onCheckedChange = {
                                displayViewModel.updateLibraryPreferences(showHiddenDevices = it)
                            },
                        )
                        StyledToggle(
                            label = "Dark map",
                            description = "Invert OpenStreetMap tiles for a darker, higher-contrast map.",
                            checked = settings.darkMapEnabled,
                            onCheckedChange = {
                                displayViewModel.updateLibraryPreferences(darkMapEnabled = it)
                            },
                        )
                        Text(
                            "Sort devices",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = sortByRecent,
                                onClick = {
                                    displayViewModel.updateLibraryPreferences(sortByRecent = true)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                modifier = Modifier.weight(1f),
                            ) { Text("Latest", maxLines = 1) }
                            SegmentedButton(
                                selected = !sortByRecent,
                                onClick = {
                                    displayViewModel.updateLibraryPreferences(sortByRecent = false)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                modifier = Modifier.weight(1f),
                            ) { Text("Name") }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        StyledButton(
                            onClick = {
                                managementVisible = false
                                showCreateGroup = true
                            },
                            backdrop = innerBackdrop,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Create group") }
                        StyledButton(
                            onClick = {
                                managementVisible = false
                                showManageGroups = true
                            },
                            backdrop = innerBackdrop,
                            modifier = Modifier.fillMaxWidth(),
                            materialButtonStyle = MaterialButtonStyle.Outlined,
                        ) { Text("Manage groups") }
                        StyledButton(
                            onClick = {
                                if (state.phase == FindMyPhase.READY) onRefresh()
                                if (networkState.accessories.isNotEmpty()) onRefreshNetwork()
                            },
                            backdrop = innerBackdrop,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busyAll,
                            materialButtonStyle = MaterialButtonStyle.Outlined,
                        ) {
                            if (busyAll) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Refreshing all devices")
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Refresh all devices")
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        StyledButton(
                            onClick = {
                                managementVisible = false
                                showSignOutWarning = true
                            },
                            backdrop = innerBackdrop,
                            modifier = Modifier.fillMaxWidth(),
                            materialButtonStyle = MaterialButtonStyle.Normal,
                        ) {
                            Text("Sign out of Apple Find My", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surface)
                .layerBackdrop(backdrop),
        )
        when (viewMode) {
            FindMyViewMode.Devices -> DeviceList(
                items = listItems,
                settings = settings,
                loading = busyAll && listItems.isEmpty(),
                refreshing = ::isRefreshing,
                onRefresh = ::refreshItem,
                onOpen = { editItemKey = it.key },
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
                contentTopPadding = chromeTopPadding + if (searchVisible) 136.dp else 76.dp,
                modifier = Modifier.fillMaxSize(),
            )

            FindMyViewMode.Map -> DeviceMap(
                items = locatedItems,
                settings = settings,
                selectedItemKey = selectedItemKey,
                onSelected = { selectedItemKey = it.key },
                refreshing = ::isRefreshing,
                onRefresh = ::refreshItem,
                onOpen = { editItemKey = it.key },
                onOpenMap = { item ->
                    item.location?.let { location ->
                        openLibraryMap(
                            context,
                            displayName(item, entryFor(item, settings)),
                            location,
                        )
                    }
                },
                backdrop = backdrop,
                restoredViewport = restoredViewport,
                onViewportChanged = { viewport ->
                    viewportLatitude = viewport.latitude
                    viewportLongitude = viewport.longitude
                    viewportZoom = viewport.zoom
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (viewMode == FindMyViewMode.Devices && searchVisible) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search devices") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            search = ""
                            searchVisible = false
                        },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close device search")
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = chromeTopPadding + 68.dp, end = 16.dp)
                    .zIndex(3f),
            )
        }

        if (viewMode == FindMyViewMode.Devices && !searchVisible) {
            StyledFloatingSurface(
                backdrop = backdrop,
                onClick = { searchVisible = true },
                contentDescription = "Search devices",
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = chromeTopPadding + 12.dp)
                    .size(48.dp)
                    .zIndex(4f),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.align(Alignment.Center))
            }
        }

        FindMyModePill(
            mode = viewMode,
            backdrop = backdrop,
            onModeChanged = { mode ->
                viewMode = mode
                viewChosenByUser = true
                if (mode != FindMyViewMode.Devices) searchVisible = false
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = chromeTopPadding + 12.dp)
                .zIndex(4f),
        )

        StyledButton(
            onClick = { managementVisible = true },
            backdrop = backdrop,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = chromeTopPadding + 12.dp, end = 16.dp)
                .width(64.dp)
                .height(48.dp)
                .zIndex(4f),
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Manage Find My",
                modifier = Modifier.size(20.dp),
            )
        }

        state.message?.let { message ->
            StyledFloatingSurface(
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .zIndex(5f),
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FindMyModePill(
    mode: FindMyViewMode,
    backdrop: LayerBackdrop,
    onModeChanged: (FindMyViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.width(144.dp).height(48.dp)) {
        StyledFloatingSurface(
            backdrop = backdrop,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(40.dp),
        ) { }
        Row(Modifier.fillMaxSize()) {
            FindMyModeSegment(
                label = "Devices",
                selected = mode == FindMyViewMode.Devices,
                onClick = { onModeChanged(FindMyViewMode.Devices) },
                modifier = Modifier.weight(1f),
            )
            FindMyModeSegment(
                label = "Map",
                selected = mode == FindMyViewMode.Map,
                onClick = { onModeChanged(FindMyViewMode.Map) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FindMyModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .semantics {
                this.selected = selected
                role = Role.Tab
                contentDescription = "$label view"
            }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = when {
                !selected -> Color.Transparent
                LocalDesignSystem.current == DesignSystem.Apple -> Color.White.copy(alpha = 0.14f)
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun SourceStatusRow(
    title: String,
    status: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun appleLibraryStatus(state: FindMyUiState): String = when (state.phase) {
    FindMyPhase.RESTORING -> "Restoring session"
    FindMyPhase.SIGNED_OUT -> "Not connected"
    FindMyPhase.SIGNING_IN -> "Signing in"
    FindMyPhase.NEEDS_TWO_FACTOR -> "Verification required"
    FindMyPhase.REFRESHING -> "Connected · refreshing ${state.devices.size} devices"
    FindMyPhase.READY -> "Connected · ${state.devices.size} devices"
    FindMyPhase.SESSION_ERROR, FindMyPhase.ERROR -> "Needs attention"
}

private fun networkLibraryStatus(state: FindMyNetworkUiState): String = when (state.phase) {
    FindMyNetworkPhase.RESTORING -> "Restoring session"
    FindMyNetworkPhase.SIGNED_OUT -> "Not connected"
    FindMyNetworkPhase.SIGNING_IN -> "Signing in"
    FindMyNetworkPhase.CHOOSE_TWO_FACTOR_METHOD,
    FindMyNetworkPhase.ENTER_TWO_FACTOR_CODE -> "Verification required"
    FindMyNetworkPhase.READY_TO_RECOVER,
    FindMyNetworkPhase.OPENING_RECOVERY,
    FindMyNetworkPhase.CHOOSE_RECOVERY_DEVICE,
    FindMyNetworkPhase.UNLOCKING_KEYCHAIN,
    FindMyNetworkPhase.CHOOSE_ACCESSORIES,
    FindMyNetworkPhase.IMPORTING_ACCESSORIES -> "Connected · accessory recovery in progress"
    FindMyNetworkPhase.REFRESHING_REPORTS -> "Connected · refreshing ${state.accessories.size} accessories"
    FindMyNetworkPhase.READY -> "Connected · ${state.accessories.size} accessories"
    FindMyNetworkPhase.ERROR -> "Needs attention"
}

@Composable
private fun DeviceList(
    items: List<FindMyDisplayItem>,
    settings: FindMyDisplaySettings,
    loading: Boolean,
    refreshing: (FindMyDisplayItem) -> Boolean,
    onRefresh: (FindMyDisplayItem) -> Unit,
    onOpen: (FindMyDisplayItem) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    hasLinkCandidate: (FindMyDisplayItem) -> Boolean,
    onDrop: (String, String) -> Unit,
    contentTopPadding: androidx.compose.ui.unit.Dp,
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

    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentTopPadding,
            end = 16.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (items.isEmpty()) {
            item(key = "empty") {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Text(
                            if (loading) "Refreshing devices" else "No devices in this view",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Text(
                            if (loading) {
                                "Loading the latest devices and locations from your connected sources."
                            } else {
                                "Change search or restore removed devices from Manage."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        if (items.size > 1) {
            item(key = "grouping-tip") {
                Text(
                    "Open a device for rename, group, combine, and visibility controls. Long-press drag remains a grouping shortcut.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
        }

        sections.forEachIndexed { sectionIndex, section ->
            item(key = "section:${section.key}") {
                val canRename = section.key != UNGROUPED_SECTION_KEY
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (sectionIndex == 0) 0.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        section.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (canRename) {
                        IconButton(
                            onClick = { onRenameGroup(section.key, section.name) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Rename ${section.name} group",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            items(section.items, key = FindMyDisplayItem::key) { item ->
                val entry = entryFor(item, settings)
                DeviceListRow(
                    item = item,
                    name = displayName(item, entry),
                    refreshing = refreshing(item),
                    hasLinkCandidate = hasLinkCandidate(item),
                    onRefresh = { onRefresh(item) },
                    onOpen = { onOpen(item) },
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
            }
        }
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
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
            .semantics {
                stateDescription = when {
                    isDropTarget -> "Grouping drop target"
                    isDragging -> "Dragging for grouping"
                    refreshing -> "Refreshing location"
                    item.location == null -> "No known location"
                    else -> "Device row"
                }
            }
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        color = when {
            isDropTarget -> MaterialTheme.colorScheme.primaryContainer
            isDragging -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceGlyph(name, size = 40)
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.location == null) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        latestReportText(item.location),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val identity = identityText(item)
                Text(
                    buildString {
                        append(if (item.isLinked) item.sourceLabel else item.locationSource?.label ?: item.sourceLabel)
                        if (identity != null) append(" · ").append(identity)
                        if (hasLinkCandidate && !item.isLinked) append(" · Possible duplicate")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onRefresh,
                    enabled = !refreshing,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh $name")
                    }
                }
                IconButton(
                    onClick = onOpen,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options for $name")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    backdrop: LayerBackdrop,
    restoredViewport: FindMyViewportState?,
    onViewportChanged: (FindMyViewportState) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.Place, null, Modifier.size(44.dp), MaterialTheme.colorScheme.outline)
                    Text("No known locations", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Refresh a device or switch to Devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        return
    }

    val normalizedKey = FindMyMapUiPolicy.normalizeSelectedKey(
        selectedKey = selectedItemKey,
        locatedKeys = items.map(FindMyDisplayItem::key),
    ) ?: return
    val initialIndex = items.indexOfFirst { it.key == normalizedKey }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { items.size })
    val scope = rememberCoroutineScope()
    var chooserVisible by rememberSaveable { mutableStateOf(false) }
    var chooserSearch by rememberSaveable { mutableStateOf("") }
    var focusRequestToken by rememberSaveable { mutableStateOf(0L) }
    var pagerSyncTargetKey by remember { mutableStateOf<String?>(null) }
    val itemKeys = items.map(FindMyDisplayItem::key)

    LaunchedEffect(itemKeys, normalizedKey) {
        val targetIndex = items.indexOfFirst { it.key == normalizedKey }.coerceAtLeast(0)
        val currentKey = items.getOrNull(pagerState.currentPage)?.key
        if (currentKey != normalizedKey || pagerState.currentPage != targetIndex) {
            pagerSyncTargetKey = normalizedKey
            pagerState.scrollToPage(targetIndex)
        }
    }

    LaunchedEffect(pagerState.isScrollInProgress, itemKeys) {
        if (!pagerState.isScrollInProgress) {
            val item = items.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
            if (pagerSyncTargetKey == item.key) {
                pagerSyncTargetKey = null
            } else if (item.key != normalizedKey) {
                onSelected(item)
                focusRequestToken += 1L
            }
        }
    }

    fun selectExplicitly(item: FindMyDisplayItem, synchronizePager: Boolean) {
        onSelected(item)
        focusRequestToken += 1L
        if (synchronizePager) {
            val index = items.indexOfFirst { it.key == item.key }
            if (index >= 0 && index != pagerState.currentPage) {
                pagerSyncTargetKey = item.key
                scope.launch { pagerState.animateScrollToPage(index) }
            }
        }
    }

    StyledBottomSheet(
        visible = chooserVisible,
        onDismiss = { chooserVisible = false },
        backdrop = backdrop,
    ) { _, _ ->
        val chooserQuery = chooserSearch.trim().lowercase(Locale.ROOT)
        val chooserItems = items.filter { item ->
            chooserQuery.isBlank() || listOf(
                displayName(item, entryFor(item, settings)),
                item.modelLabel.orEmpty(),
                identityText(item).orEmpty(),
            ).joinToString(" ").lowercase(Locale.ROOT).contains(chooserQuery)
        }
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            Text(
                "All located devices",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = chooserSearch,
                onValueChange = { chooserSearch = it },
                placeholder = { Text("Search located devices") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chooserItems, key = FindMyDisplayItem::key) { item ->
                    val name = displayName(item, entryFor(item, settings))
                    val isSelected = item.key == normalizedKey
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .semantics {
                                selected = isSelected
                                stateDescription = if (isSelected) "Selected device" else "Located device"
                            }
                            .clickable {
                                chooserVisible = false
                                chooserSearch = ""
                                selectExplicitly(item, synchronizePager = true)
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DeviceGlyph(name, size = 40)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(
                                    latestReportText(item.location),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            if (isSelected) {
                                Text(
                                    "Selected",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Box(modifier) {
        OpenStreetMap(
            items = items,
            settings = settings,
            selectedItemKey = normalizedKey,
            focusRequestToken = focusRequestToken,
            restoredViewport = restoredViewport,
            onViewportChanged = onViewportChanged,
            onMarkerSelected = { key ->
                items.firstOrNull { it.key == key }?.let { item ->
                    selectExplicitly(item, synchronizePager = true)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        HorizontalPager(
            state = pagerState,
            key = { items[it].key },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .heightIn(min = 144.dp, max = 184.dp),
        ) { page ->
            val item = items[page]
            SelectedDeviceCard(
                item = item,
                name = displayName(item, entryFor(item, settings)),
                refreshing = refreshing(item),
                positionLabel = "${page + 1}/${items.size}",
                onRefresh = { onRefresh(item) },
                onOpen = { onOpen(item) },
                onOpenMap = { onOpenMap(item) },
                onChoose = { chooserVisible = true },
                backdrop = backdrop,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SelectedDeviceCard(
    item: FindMyDisplayItem,
    name: String,
    refreshing: Boolean,
    positionLabel: String,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
    onOpenMap: () -> Unit,
    onChoose: () -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    StyledFloatingSurface(
        backdrop = backdrop,
        selected = true,
        contentDescription = "Selected device $name",
        modifier = modifier.fillMaxWidth().heightIn(min = 120.dp, max = 160.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeviceGlyph(name, size = 44)
                Column(
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        name,
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        libraryLocationDescription(item.location),
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        latestReportText(item.location),
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        identityText(item) ?: (item.locationSource?.label ?: item.sourceLabel),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompactCardAction(
                    icon = Icons.Default.Place,
                    label = "Directions",
                    loading = false,
                    onClick = onOpenMap,
                    modifier = Modifier.weight(1f),
                )
                CompactCardAction(
                    icon = Icons.Default.Refresh,
                    label = "Refresh",
                    loading = refreshing,
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                )
                CompactCardAction(
                    icon = Icons.Default.MoreVert,
                    label = "More",
                    loading = false,
                    onClick = onOpen,
                    modifier = Modifier.weight(1f),
                )
                CompactCardAction(
                    icon = Icons.Default.Search,
                    label = "All $positionLabel",
                    loading = false,
                    onClick = onChoose,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CompactCardAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .semantics { contentDescription = label }
            .clickable(enabled = !loading, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    label,
                    fontSize = 10.5.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            modifier = Modifier.size(48.dp).clickable(enabled = !loading, onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(icon, contentDescription = label, modifier = Modifier.size(23.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDetailsDialog(
    item: FindMyDisplayItem,
    entry: FindMyDisplayEntry,
    backdrop: LayerBackdrop,
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
    StyledBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        backdrop = backdrop,
        skipPartiallyExpanded = true,
        gesturesEnabled = false,
    ) { innerBackdrop, _ ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 560.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceGlyph(displayName(item, entry))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        displayName(item, entry),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
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
            Text(
                "Latest report",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp),
            )
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
                Text("Group: $it", modifier = Modifier.padding(top = 6.dp))
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
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RoundCardAction(Icons.Default.Refresh, "Refresh", refreshing, onRefresh)
                onOpenMap?.let { openMap ->
                    RoundCardAction(Icons.Default.Place, "Map", false, openMap)
                }
                RoundCardAction(Icons.Default.Edit, "Rename", false, onRename)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            StyledButton(
                onClick = onGroup,
                backdrop = innerBackdrop,
                modifier = Modifier.fillMaxWidth(),
                materialButtonStyle = MaterialButtonStyle.Outlined,
            ) { Text("Move to group") }
            StyledButton(
                onClick = onLink,
                backdrop = innerBackdrop,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                materialButtonStyle = MaterialButtonStyle.Outlined,
            ) {
                Text(
                    if (item.isLinked) "Separate Apple and network records"
                    else "Combine with another source",
                )
            }
            StyledButton(
                onClick = onToggleVisible,
                backdrop = innerBackdrop,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                materialButtonStyle = MaterialButtonStyle.Normal,
            ) {
                Text(
                    if (entry.visible) "Remove from view" else "Restore to view",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            StyledButton(
                onClick = onDismiss,
                backdrop = innerBackdrop,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Done") }
        }
    }
}
private data class MarkerGlyphCacheKey(
    val glyph: String,
    val color: Int,
    val densityBucket: Int,
)

@Composable
private fun OpenStreetMap(
    items: List<FindMyDisplayItem>,
    settings: FindMyDisplaySettings,
    selectedItemKey: String,
    focusRequestToken: Long,
    restoredViewport: FindMyViewportState?,
    onViewportChanged: (FindMyViewportState) -> Unit,
    onMarkerSelected: (String) -> Unit,
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
    val markers = remember(map) { mutableMapOf<String, Marker>() }
    val markerVisuals = remember(map) { mutableMapOf<String, MarkerGlyphCacheKey>() }
    val glyphCache = remember(map) { mutableMapOf<MarkerGlyphCacheKey, BitmapDrawable>() }
    var previousSelectedKey by remember { mutableStateOf<String?>(null) }
    var initialCameraApplied by remember { mutableStateOf(false) }

    val selectedLocation = items.firstOrNull { it.key == selectedItemKey }?.location
    val currentFocusedSnapshot = FindMyFocusedDeviceSnapshot(
        key = selectedItemKey,
        coordinate = selectedLocation?.let { FindMyMapCoordinate(it.latitude, it.longitude) },
    )
    var previousFocusedSnapshot by remember { mutableStateOf(currentFocusedSnapshot) }
    var previousFocusRequestToken by remember { mutableStateOf(focusRequestToken) }

    LaunchedEffect(map, settings.darkMapEnabled) {
        map.overlayManager.tilesOverlay.setColorFilter(
            if (settings.darkMapEnabled) TilesOverlay.INVERT_COLORS else null,
        )
        map.invalidate()
    }

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
            val center = map.mapCenter
            onViewportChanged(
                FindMyViewportState(
                    latitude = center.latitude,
                    longitude = center.longitude,
                    zoom = map.zoomLevelDouble,
                ),
            )
            lifecycleOwner.lifecycle.removeObserver(observer)
            map.onPause()
            map.onDetach()
        }
    }

    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { mapView ->
            val desired = items.associateBy(FindMyDisplayItem::key)
            val removedKeys = markers.keys.filterNot(desired::containsKey)
            removedKeys.forEach { key ->
                markers.remove(key)?.let(mapView.overlays::remove)
                markerVisuals.remove(key)
            }

            val densityBucket = (context.resources.displayMetrics.density * 100f).roundToInt()
            val selectionChanged = previousSelectedKey != selectedItemKey
            items.forEach { item ->
                val location = item.location ?: return@forEach
                val name = displayName(item, entryFor(item, settings))
                val isNew = item.key !in markers
                val marker = markers.getOrPut(item.key) {
                    Marker(mapView).also { created ->
                        created.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        mapView.overlays.add(created)
                    }
                }
                val currentPosition = marker.position
                if (
                    currentPosition == null ||
                    currentPosition.latitude != location.latitude ||
                    currentPosition.longitude != location.longitude
                ) {
                    marker.position = GeoPoint(location.latitude, location.longitude)
                }
                marker.title = name
                marker.snippet = latestReportText(location)
                marker.setOnMarkerClickListener { _, _ ->
                    onMarkerSelected(item.key)
                    true
                }

                val glyph = deviceGlyph(name)
                val color = if (item.key == selectedItemKey) selected else primary
                val visualKey = MarkerGlyphCacheKey(glyph, color, densityBucket)
                val oldVisualKey = markerVisuals[item.key]
                val selectionVisualChanged = selectionChanged &&
                    (item.key == previousSelectedKey || item.key == selectedItemKey)
                if (isNew || selectionVisualChanged || oldVisualKey != visualKey) {
                    marker.icon = glyphCache.getOrPut(visualKey) {
                        markerDrawable(context, glyph, color)
                    }
                    markerVisuals[item.key] = visualKey
                }
            }
            previousSelectedKey = selectedItemKey

            if (!initialCameraApplied) {
                initialCameraApplied = true
                val points = items.mapNotNull { item ->
                    item.location?.let { GeoPoint(it.latitude, it.longitude) }
                }
                mapView.post {
                    val viewport = restoredViewport
                    if (viewport != null) {
                        mapView.controller.setZoom(viewport.zoom)
                        mapView.controller.setCenter(GeoPoint(viewport.latitude, viewport.longitude))
                    } else if (points.size == 1) {
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

    LaunchedEffect(
        selectedItemKey,
        selectedLocation?.latitude,
        selectedLocation?.longitude,
        focusRequestToken,
    ) {
        val current = FindMyFocusedDeviceSnapshot(
            key = selectedItemKey,
            coordinate = selectedLocation?.let { FindMyMapCoordinate(it.latitude, it.longitude) },
        )
        val explicitSelectionChanged = focusRequestToken != previousFocusRequestToken
        val shouldFocus = FindMyMapUiPolicy.shouldFocusCamera(
            previous = previousFocusedSnapshot,
            current = current,
            explicitSelectionChanged = explicitSelectionChanged,
        )
        previousFocusedSnapshot = current
        previousFocusRequestToken = focusRequestToken
        if (shouldFocus) {
            current.coordinate?.let { coordinate ->
                map.post {
                    map.controller.animateTo(GeoPoint(coordinate.latitude, coordinate.longitude))
                }
            }
        }
    }
}

private fun markerDrawable(context: Context, glyph: String, color: Int): BitmapDrawable {
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
    val baseline = size / 2f - (paint.ascent() + paint.descent()) / 2f
    canvas.drawText(glyph, size / 2f, baseline, paint)
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
