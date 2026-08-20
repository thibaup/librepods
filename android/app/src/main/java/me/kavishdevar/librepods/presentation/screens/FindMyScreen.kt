package me.kavishdevar.librepods.presentation.screens

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import me.kavishdevar.librepods.features.findmy.FindMyDisplayViewModel
import me.kavishdevar.librepods.features.findmy.FindMyNetworkAnisetteState
import me.kavishdevar.librepods.features.findmy.FindMyNetworkPhase
import me.kavishdevar.librepods.features.findmy.FindMyNetworkUiState
import me.kavishdevar.librepods.features.findmy.FindMyNetworkViewModel
import me.kavishdevar.librepods.features.findmy.FindMyPhase
import me.kavishdevar.librepods.features.findmy.FindMyUiState
import me.kavishdevar.librepods.features.findmy.FindMyViewModel
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem

@Composable
fun FindMyScreen(
    viewModel: FindMyViewModel = viewModel(),
    networkViewModel: FindMyNetworkViewModel = viewModel(),
) {
    val displayViewModel: FindMyDisplayViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    val networkState by networkViewModel.uiState.collectAsState()
    val displayState by displayViewModel.uiState.collectAsState()
    var showSourcesSetup by rememberSaveable { mutableStateOf(false) }
    var recoveryPasscode by remember { mutableStateOf("") }

    LaunchedEffect(state.appleId) {
        if (state.appleId.isNotBlank()) networkViewModel.setAppleIdHint(state.appleId)
    }
    LaunchedEffect(state.appleId, networkState.appleId) {
        val accounts = listOf(state.appleId, networkState.appleId)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
        displayViewModel.setAccount(accounts.joinToString("|"))
    }

    state.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("Find My error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text("OK") }
            },
        )
    }
    networkState.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = networkViewModel::dismissError,
            title = { Text("Find My network error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = networkViewModel::dismissError) { Text("OK") }
            },
        )
    }
    networkState.selectedRecoverySerial?.takeIf {
        networkState.phase == FindMyNetworkPhase.CHOOSE_RECOVERY_DEVICE
    }?.let { serial ->
        val device = networkState.recoveryDevices.firstOrNull { it.serial == serial }
        AlertDialog(
            onDismissRequest = {
                recoveryPasscode = ""
                networkViewModel.selectRecoveryDevice(null)
            },
            title = { Text("Unlock Apple keychain") },
            text = {
                Column {
                    Text(
                        "Enter the screen-lock passcode for " +
                            (device?.description ?: serial) + ". This is sent only to Apple's " +
                            "escrow service and is never stored.",
                    )
                    OutlinedTextField(
                        value = recoveryPasscode,
                        onValueChange = { recoveryPasscode = it },
                        label = { Text("Device passcode") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Text(
                        "Attempt ${networkState.passcodeAttempts + 1} of 3",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = recoveryPasscode.isNotBlank(),
                    onClick = {
                        val submitted = recoveryPasscode
                        recoveryPasscode = ""
                        networkViewModel.submitRecoveryPasscode(submitted)
                    },
                ) { Text("Unlock") }
            },
            dismissButton = {
                TextButton(onClick = {
                    recoveryPasscode = ""
                    networkViewModel.selectRecoveryDevice(null)
                }) { Text("Cancel") }
            },
        )
    }
    if (showSourcesSetup) {
        FindMySourcesSetup(
            appleState = state,
            appleViewModel = viewModel,
            networkState = networkState,
            networkViewModel = networkViewModel,
            onBack = { showSourcesSetup = false },
        )
        return
    }

    // The two Find My planes are independent. Either a restored Apple-account snapshot or a
    // signed-in network account is enough to enter the library; the setup screen remains
    // available from the library menu for the other source.
    val appleSourceAvailable = state.phase == FindMyPhase.READY || state.devices.isNotEmpty()
    val networkSourceAvailable = networkState.accessories.isNotEmpty() ||
        (networkState.appleId.isNotBlank() && networkState.phase in setOf(
            FindMyNetworkPhase.READY,
            FindMyNetworkPhase.READY_TO_RECOVER,
            FindMyNetworkPhase.OPENING_RECOVERY,
            FindMyNetworkPhase.CHOOSE_RECOVERY_DEVICE,
            FindMyNetworkPhase.UNLOCKING_KEYCHAIN,
            FindMyNetworkPhase.CHOOSE_ACCESSORIES,
            FindMyNetworkPhase.IMPORTING_ACCESSORIES,
            FindMyNetworkPhase.IMPORTING_EXPORT,
            FindMyNetworkPhase.ENTER_EXPORT_PASSCODE,
            FindMyNetworkPhase.REFRESHING_REPORTS,
        ))
    val canShowLibrary = appleSourceAvailable || networkSourceAvailable

    when {
        canShowLibrary -> FindMyLibraryList(
            state = state,
            onRefresh = viewModel::refresh,
            onSignOut = viewModel::signOut,
            onMessageShown = viewModel::consumeMessage,
            networkState = networkState,
            displayState = displayState,
            displayViewModel = displayViewModel,
            onRefreshNetwork = networkViewModel::refreshReports,
            onRefreshWebDevice = viewModel::refreshDevice,
            onRefreshNetworkAccessory = networkViewModel::refreshAccessory,
            onOpenSourcesSetup = { showSourcesSetup = true },
        )
        // Either data plane can open the library; if neither is available, keep the focused
        // setup/restoration flow visible.
        state.phase == FindMyPhase.RESTORING ->
            LoadingFindMy(
                "Restoring your Apple session…",
                onOpenSourcesSetup = { showSourcesSetup = true },
            )
        state.phase == FindMyPhase.SESSION_ERROR ||
            state.phase == FindMyPhase.SIGNED_OUT ||
            state.phase == FindMyPhase.NEEDS_TWO_FACTOR ||
            (state.phase == FindMyPhase.ERROR && state.devices.isEmpty()) ->
            FindMySourcesSetup(
                appleState = state,
                appleViewModel = viewModel,
                networkState = networkState,
                networkViewModel = networkViewModel,
                onBack = null,
            )
        state.phase == FindMyPhase.SIGNING_IN && state.devices.isEmpty() ->
            LoadingFindMy(
                "Signing in securely with Apple…",
                onOpenSourcesSetup = { showSourcesSetup = true },
            )
        else -> FindMySourcesSetup(
            appleState = state,
            appleViewModel = viewModel,
            networkState = networkState,
            networkViewModel = networkViewModel,
            onBack = null,
        )
    }
}

@Composable
private fun FindMySourcesSetup(
    appleState: FindMyUiState,
    appleViewModel: FindMyViewModel,
    networkState: FindMyNetworkUiState,
    networkViewModel: FindMyNetworkViewModel,
    onBack: (() -> Unit)?,
) {
    val bothSourcesConfigured = appleState.appleId.isNotBlank() &&
        networkState.appleId.isNotBlank() &&
        networkState.phase !in setOf(
            FindMyNetworkPhase.SIGNED_OUT,
            FindMyNetworkPhase.RESTORING,
        )
    var showCombineExplanation by rememberSaveable { mutableStateOf(true) }
    FindMyFormContainer {
        onBack?.let { goBack ->
            OutlinedButton(onClick = goBack, modifier = Modifier.align(Alignment.Start)) {
                Text("Back to Find My")
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(
            "Find My sources",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Choose which Apple data sources are connected. You can use either source or both " +
                "together; their records use different IDs and are never merged silently.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
        )
        if (bothSourcesConfigured && showCombineExplanation) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Both sources are enabled",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "The same physical device can appear twice because Apple-account and " +
                                "Find My-network records have different identifiers. Open a device " +
                                "and use ‘Combine with another source’ only after you have confirmed " +
                                "the two records are the same device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    IconButton(onClick = { showCombineExplanation = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss combine explanation")
                    }
                }
            }
        }
        FindMyAppleAccountCard(state = appleState, viewModel = appleViewModel)
        Spacer(Modifier.height(16.dp))
        FindMyNetworkCard(state = networkState, viewModel = networkViewModel)
    }
}

/** The iCloud/Apple-account login and session controls used by the combined source setup. */
@Composable
private fun FindMyAppleAccountCard(
    state: FindMyUiState,
    viewModel: FindMyViewModel,
) {
    var appleId by rememberSaveable(state.appleId) { mutableStateOf(state.appleId) }
    var password by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "iCloud / Apple account",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Apple's Find My account service for your devices and AirPods",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            when (state.phase) {
                FindMyPhase.RESTORING -> NetworkBusy("Restoring the encrypted Apple session…")

                FindMyPhase.SIGNED_OUT, FindMyPhase.ERROR -> {
                    if (state.phase == FindMyPhase.ERROR && state.appleId.isNotBlank()) {
                        Text(
                            state.errorMessage ?: "The Apple session needs attention.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                        Button(
                            onClick = viewModel::retrySavedSession,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text("Retry Apple session") }
                        TextButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                            Text("Sign out and use another account")
                        }
                    } else {
                        Text(
                            "Sign in to load the locations Apple currently has for your devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        OutlinedTextField(
                            value = appleId,
                            onValueChange = { appleId = it },
                            label = { Text("Apple Account") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        )
                        Button(
                            onClick = {
                                val submitted = password
                                password = ""
                                viewModel.signIn(appleId, submitted)
                            },
                            enabled = appleId.isNotBlank() && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text("Sign in to iCloud Find My") }
                    }
                }

                FindMyPhase.SIGNING_IN, FindMyPhase.REFRESHING ->
                    NetworkBusy(
                        if (state.phase == FindMyPhase.REFRESHING) {
                            "Refreshing Apple device locations…"
                        } else {
                            "Signing in securely with Apple…"
                        },
                    )

                FindMyPhase.NEEDS_TWO_FACTOR -> {
                    Text(
                        "Enter the six-digit code from a trusted Apple device for ${state.appleId}.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("Verification code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    Button(
                        onClick = {
                            val submitted = verificationCode
                            verificationCode = ""
                            viewModel.submitTwoFactor(submitted)
                        },
                        enabled = verificationCode.length == 6,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) { Text("Verify Apple account") }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = viewModel::requestNewTwoFactorCode) {
                            Text("Send new code")
                        }
                        TextButton(onClick = viewModel::signOut) { Text("Use another account") }
                    }
                }

                FindMyPhase.READY -> {
                    Text(
                        "Signed in as ${state.appleId.ifBlank { "Apple account" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Text(
                        "${state.devices.size} Apple device${if (state.devices.size == 1) "" else "s"} available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = viewModel::refresh, modifier = Modifier.weight(1f)) {
                            Text("Refresh")
                        }
                        OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.weight(1f)) {
                            Text("Sign out")
                        }
                    }
                    state.lastUpdatedMillis?.let {
                        Text(
                            "Updated ${relativeTime(it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                FindMyPhase.SESSION_ERROR -> {
                    Text(
                        state.errorMessage ?: "The Apple session needs attention.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Button(
                        onClick = viewModel::retrySavedSession,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text("Retry Apple session") }
                    TextButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                        Text("Sign out and use another account")
                    }
                }
            }
            Text(
                "Passwords and verification codes are used only for Apple's sign-in flow; " +
                    "the encrypted session is kept in Android Keystore-backed storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
internal fun FindMyNetworkCard(
    state: FindMyNetworkUiState,
    viewModel: FindMyNetworkViewModel,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Find My network",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Separate crowd-sourced network session for AirPods, AirTags, and compatible tags",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "This login is separate from the iCloud card above. When both are ready, their " +
                    "records are shown together and can be combined manually.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
            )
            Text(
                text = when (state.anisetteState) {
                    FindMyNetworkAnisetteState.NOT_CHECKED -> "On-device sign-in not checked"
                    FindMyNetworkAnisetteState.CHECKING -> "Preparing private on-device sign-in…"
                    FindMyNetworkAnisetteState.LOCAL_READY -> "On-device sign-in ready"
                    FindMyNetworkAnisetteState.REMOTE_FALLBACK ->
                        "On-device sign-in unavailable; secure network bootstrap will use the configured server"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (state.anisetteState) {
                    FindMyNetworkAnisetteState.LOCAL_READY -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 8.dp),
            )
            FindMyNetworkContent(
                state = state,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun FindMyNetworkContent(
    state: FindMyNetworkUiState,
    viewModel: FindMyNetworkViewModel,
) {
    var appleId by rememberSaveable(state.appleId) { mutableStateOf(state.appleId) }
    var password by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var exportPasscode by remember { mutableStateOf("") }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importOpenTagViewerExport)
    }
    val chooseExport = {
        exportPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(4_000)
            viewModel.consumeMessage()
        }
    }

    state.message?.let { message ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(message, modifier = Modifier.padding(12.dp))
        }
    }

    when (state.phase) {
        FindMyNetworkPhase.RESTORING -> NetworkBusy("Restoring encrypted network session…")

        FindMyNetworkPhase.SIGNED_OUT -> {
            Text(
                text = "Sign in once to recover your accessory owner keys directly from " +
                    "Apple, then decrypt their real Find My network reports on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedTextField(
                value = appleId,
                onValueChange = { appleId = it },
                label = { Text("Apple Account") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Button(
                onClick = {
                    val submitted = password
                    password = ""
                    viewModel.signIn(appleId, submitted)
                },
                enabled = appleId.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Sign in to Find My network") }
            Text(
                text = "FindMy.py account state can include reusable credentials and tokens. " +
                    "LibrePods encrypts that state and all recovered private keys with a " +
                    "non-exportable Android Keystore key. Passwords, verification codes, and " +
                    "device passcodes are never logged or stored separately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        FindMyNetworkPhase.SIGNING_IN -> NetworkBusy("Signing in securely with Apple…")

        FindMyNetworkPhase.CHOOSE_TWO_FACTOR_METHOD -> {
            Text(
                "Choose where Apple should send the verification code.",
                modifier = Modifier.padding(top = 16.dp),
            )
            state.twoFactorMethods.forEach { method ->
                OutlinedButton(
                    onClick = { viewModel.chooseTwoFactorMethod(method.index) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(method.label) }
            }
        }

        FindMyNetworkPhase.ENTER_TWO_FACTOR_CODE -> {
            Text(
                "Enter the six-digit Apple verification code.",
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedTextField(
                value = verificationCode,
                onValueChange = { verificationCode = it.filter(Char::isDigit).take(6) },
                label = { Text("Verification code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Button(
                onClick = {
                    val submitted = verificationCode
                    verificationCode = ""
                    viewModel.submitTwoFactorCode(submitted)
                },
                enabled = verificationCode.length == 6,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("Verify") }
        }

        FindMyNetworkPhase.READY_TO_RECOVER -> {
            Text(
                text = "Signed in as ${state.appleId}. Recover the owner keys Apple keeps in " +
                    "your encrypted iCloud keychain. You will choose a trusted device and enter " +
                    "that device's screen-lock passcode.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Button(
                onClick = viewModel::openRecovery,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Recover accessory keys") }
            OutlinedButton(
                onClick = chooseExport,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Import OpenTagViewer export") }
            Text(
                text = "Use an OpenTagViewer ZIP if Apple's recovery record has no usable " +
                    "owner keys. Plain and locked AES exports are supported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out and erase network keys")
            }
        }

        FindMyNetworkPhase.OPENING_RECOVERY -> NetworkBusy("Opening Apple's keychain recovery…")

        FindMyNetworkPhase.CHOOSE_RECOVERY_DEVICE -> {
            Text(
                "Choose a trusted Apple device whose screen-lock passcode you know.",
                modifier = Modifier.padding(top = 16.dp),
            )
            state.recoveryDevices.forEach { device ->
                OutlinedButton(
                    onClick = { viewModel.selectRecoveryDevice(device.serial) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(device.description)
                        Text(
                            device.serial,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = chooseExport,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Import OpenTagViewer export instead") }
            TextButton(onClick = viewModel::cancelRecovery, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel recovery")
            }
        }

        FindMyNetworkPhase.UNLOCKING_KEYCHAIN -> NetworkBusy("Unlocking the encrypted keychain…")

        FindMyNetworkPhase.CHOOSE_ACCESSORIES -> {
            Text(
                "Choose which recovered accessories to keep on this phone.",
                modifier = Modifier.padding(top = 16.dp),
            )
            state.candidates.forEach { candidate ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = candidate.beaconId in state.selectedBeaconIds,
                        onCheckedChange = { viewModel.toggleCandidate(candidate.beaconId) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(candidate.label, style = MaterialTheme.typography.titleMedium)
                        if (candidate.details.isNotBlank()) {
                            Text(
                                candidate.details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Button(
                onClick = viewModel::importSelectedAccessories,
                enabled = state.selectedBeaconIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Import ${state.selectedBeaconIds.size} selected") }
            TextButton(onClick = viewModel::cancelRecovery, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }

        FindMyNetworkPhase.IMPORTING_ACCESSORIES ->
            NetworkBusy("Encrypting recovered accessory keys…")

        FindMyNetworkPhase.IMPORTING_EXPORT ->
            NetworkBusy("Checking and encrypting the OpenTagViewer export…")

        FindMyNetworkPhase.ENTER_EXPORT_PASSCODE -> {
            Text(
                text = "This export is locked. Enter the separate 12-character code created " +
                    "with the export—not your Apple password or phone passcode.",
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "Keep that code separate from the ZIP when sharing or backing it up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedTextField(
                value = exportPasscode,
                onValueChange = { exportPasscode = it.take(32) },
                label = { Text("Export code") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Button(
                onClick = {
                    val submitted = exportPasscode
                    exportPasscode = ""
                    viewModel.submitExportPasscode(submitted)
                },
                enabled = exportPasscode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("Unlock and import") }
            TextButton(
                onClick = {
                    exportPasscode = ""
                    viewModel.cancelExportPasscode()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose another file") }
        }

        FindMyNetworkPhase.REFRESHING_REPORTS -> {
            NetworkBusy("Downloading and decrypting Find My network reports…")
        }

        FindMyNetworkPhase.READY -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = viewModel::refreshReports,
                    modifier = Modifier.weight(1f),
                ) { Text("Refresh") }
                OutlinedButton(
                    onClick = viewModel::openRecovery,
                    modifier = Modifier.weight(1f),
                ) { Text("Recover more") }
            }
            OutlinedButton(
                onClick = chooseExport,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("Import OpenTagViewer export") }
            state.lastUpdatedMillis?.let {
                Text(
                    "Network reports updated ${relativeTime(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            TextButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out and erase recovered keys")
            }
        }

        FindMyNetworkPhase.ERROR -> {
            Text(
                "The encrypted network session could not be restored. Retry first; reset only " +
                    "if you want to erase its account state and recovered keys.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Button(
                onClick = viewModel::retryRestore,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Retry encrypted session") }
            TextButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                Text("Reset network setup")
            }
        }
    }
}

@Composable
private fun NetworkBusy(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoadingFindMy(label: String, onOpenSourcesSetup: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(label, modifier = Modifier.padding(top = 16.dp))
        onOpenSourcesSetup?.let { openSetup ->
            OutlinedButton(onClick = openSetup, modifier = Modifier.padding(top = 16.dp)) {
                Text("Open Find My sources setup")
            }
        }
    }
}

@Composable
private fun FindMyFormContainer(content: @Composable ColumnScope.() -> Unit) {
    val materialDesign = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (materialDesign) 40.dp else
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 100.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = topPadding, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

private fun relativeTime(timestampMillis: Long): String = DateUtils.getRelativeTimeSpanString(
    timestampMillis,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
).toString()
