package me.kavishdevar.librepods.presentation.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.features.findmy.FindMyDisplayUiState
import me.kavishdevar.librepods.features.findmy.FindMyDisplayViewModel
import me.kavishdevar.librepods.features.findmy.FindMyNetworkAnisetteState
import me.kavishdevar.librepods.features.findmy.FindMyNetworkPhase
import me.kavishdevar.librepods.features.findmy.FindMyNetworkUiState
import me.kavishdevar.librepods.features.findmy.FindMyNetworkViewModel
import me.kavishdevar.librepods.features.findmy.FindMyPhase
import me.kavishdevar.librepods.features.findmy.FindMyUiState
import me.kavishdevar.librepods.features.findmy.FindMyViewModel
import me.kavishdevar.librepods.presentation.components.MaterialButtonStyle
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledFloatingSurface
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem

@Composable
fun FindMyScreen(
    viewModel: FindMyViewModel = viewModel(),
    networkViewModel: FindMyNetworkViewModel = viewModel(),
) {
    FindMyCompactTheme {
        FindMyScreenContent(viewModel, networkViewModel)
    }
}

@Composable
private fun FindMyScreenContent(
    viewModel: FindMyViewModel,
    networkViewModel: FindMyNetworkViewModel,
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
            displayState = displayState,
            displayViewModel = displayViewModel,
            onBack = { showSourcesSetup = false },
        )
        return
    }

    // The two Find My planes are independent. Either a restored Apple-account snapshot or a
    // signed-in network account is enough to enter the library; the setup screen remains
    // available from the library menu for the other source.
    val appleSourceAvailable = state.devices.isNotEmpty() &&
        state.phase in setOf(FindMyPhase.READY, FindMyPhase.REFRESHING)
    val networkSourceAvailable =
        (networkState.accessories.isNotEmpty() &&
            networkState.phase in setOf(
                FindMyNetworkPhase.READY,
                FindMyNetworkPhase.REFRESHING_REPORTS,
            )) ||
        (networkState.appleId.isNotBlank() && networkState.phase in setOf(
            FindMyNetworkPhase.READY,
            FindMyNetworkPhase.READY_TO_RECOVER,
            FindMyNetworkPhase.OPENING_RECOVERY,
            FindMyNetworkPhase.CHOOSE_RECOVERY_DEVICE,
            FindMyNetworkPhase.UNLOCKING_KEYCHAIN,
            FindMyNetworkPhase.CHOOSE_ACCESSORIES,
            FindMyNetworkPhase.IMPORTING_ACCESSORIES,
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
                displayState = displayState,
                displayViewModel = displayViewModel,
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
            displayState = displayState,
            displayViewModel = displayViewModel,
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
    displayState: FindMyDisplayUiState,
    displayViewModel: FindMyDisplayViewModel,
    onBack: (() -> Unit)?,
) {
    val bothSourcesConfigured = appleState.devices.isNotEmpty() &&
        networkState.accessories.isNotEmpty()
    FindMyFormContainer { backdrop ->
        onBack?.let { goBack ->
            StyledButton(
                onClick = goBack,
                backdrop = backdrop,
                materialButtonStyle = MaterialButtonStyle.Outlined,
                modifier = Modifier.align(Alignment.Start),
            ) { Text("Back to Find My") }
            Spacer(Modifier.height(16.dp))
        }
        Text(
            "Find My sources",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Use either Apple data source or both. Their identifiers are separate, so records are never merged without your confirmation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
        )
        if (bothSourcesConfigured && displayState.settings.showCombineNotice) {
            StyledFloatingSurface(
                backdrop = backdrop,
                selected = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Both sources are enabled",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "A physical device can appear twice. Use Combine only after confirming the two source records are the same device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            displayViewModel.updateLibraryPreferences(showCombineNotice = false)
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss combine explanation")
                    }
                }
            }
        }
        FindMyAppleAccountCard(
            state = appleState,
            viewModel = appleViewModel,
            backdrop = backdrop,
        )
        Spacer(Modifier.height(16.dp))
        FindMyNetworkCardContent(
            state = networkState,
            viewModel = networkViewModel,
            backdrop = backdrop,
        )
    }
}

@Composable
private fun FindMyAppleAccountCard(
    state: FindMyUiState,
    viewModel: FindMyViewModel,
    backdrop: LayerBackdrop,
) {
    var appleId by rememberSaveable(state.appleId) { mutableStateOf(state.appleId) }
    var password by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }

    StyledFloatingSurface(
        backdrop = backdrop,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            FindMySourceStatusHeader(
                title = "Apple account",
                subtitle = "Device locations from Apple's account service",
                status = appleSourceSetupStatus(state),
            )
            when (state.phase) {
                FindMyPhase.RESTORING -> NetworkBusy("Restoring encrypted Apple session…")

                FindMyPhase.SIGNED_OUT, FindMyPhase.ERROR -> {
                    if (state.phase == FindMyPhase.ERROR && state.appleId.isNotBlank()) {
                        Text(
                            state.errorMessage ?: "The Apple session needs attention.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        StyledButton(
                            onClick = viewModel::retrySavedSession,
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text("Retry Apple session") }
                    } else {
                        Text(
                            "Sign in to load the locations Apple currently has for your devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
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
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
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
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        StyledButton(
                            onClick = {
                                val submitted = password
                                password = ""
                                viewModel.signIn(appleId, submitted)
                            },
                            backdrop = backdrop,
                            enabled = appleId.isNotBlank() && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
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
                        modifier = Modifier.padding(top = 12.dp),
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
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    StyledButton(
                        onClick = {
                            val submitted = verificationCode
                            verificationCode = ""
                            viewModel.submitTwoFactor(submitted)
                        },
                        backdrop = backdrop,
                        enabled = verificationCode.length == 6,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Verify Apple account") }
                    StyledButton(
                        onClick = viewModel::requestNewTwoFactorCode,
                        backdrop = backdrop,
                        materialButtonStyle = MaterialButtonStyle.Normal,
                        modifier = Modifier.align(Alignment.Start),
                    ) { Text("Send new code") }
                }

                FindMyPhase.READY -> {
                    Text(
                        "Signed in as ${state.appleId.ifBlank { "Apple account" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        "${state.devices.size} Apple device${if (state.devices.size == 1) "" else "s"} available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    StyledButton(
                        onClick = viewModel::refresh,
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) { Text("Refresh Apple locations") }
                    state.lastUpdatedMillis?.let {
                        Text(
                            "Updated ${relativeTime(it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                FindMyPhase.SESSION_ERROR -> {
                    Text(
                        state.errorMessage ?: "The Apple session needs attention.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    StyledButton(
                        onClick = viewModel::retrySavedSession,
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) { Text("Retry Apple session") }
                }
            }
            Text(
                "Security: passwords and verification codes are used only during Apple sign-in. The reusable session is kept in Android Keystore-backed encrypted storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
internal fun FindMyNetworkCard(
    state: FindMyNetworkUiState,
    viewModel: FindMyNetworkViewModel,
) {
    val backdrop = rememberLayerBackdrop()
    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .layerBackdrop(backdrop),
        )
        FindMyNetworkCardContent(state = state, viewModel = viewModel, backdrop = backdrop)
    }
}

@Composable
private fun FindMyNetworkCardContent(
    state: FindMyNetworkUiState,
    viewModel: FindMyNetworkViewModel,
    backdrop: LayerBackdrop,
) {
    StyledFloatingSurface(
        backdrop = backdrop,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            FindMySourceStatusHeader(
                title = "Find My network",
                subtitle = "Crowd-sourced reports for AirPods, AirTags, and compatible tags",
                status = networkSourceSetupStatus(state),
            )
            Text(
                "This account session is separate from the Apple-account source above. When both are ready, records remain distinct until you combine them manually.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = when (state.anisetteState) {
                    FindMyNetworkAnisetteState.NOT_CHECKED -> "Sign-in bootstrap: not checked"
                    FindMyNetworkAnisetteState.CHECKING -> "Sign-in bootstrap: preparing on-device flow…"
                    FindMyNetworkAnisetteState.LOCAL_READY -> "Sign-in bootstrap: on-device ready"
                    FindMyNetworkAnisetteState.REMOTE_FALLBACK ->
                        "Sign-in bootstrap: configured secure server fallback"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            FindMyNetworkContent(state = state, viewModel = viewModel, backdrop = backdrop)
        }
    }
}

@Composable
private fun FindMySourceStatusHeader(
    title: String,
    subtitle: String,
    status: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}

private fun appleSourceSetupStatus(state: FindMyUiState): String = when (state.phase) {
    FindMyPhase.RESTORING -> "Restoring"
    FindMyPhase.SIGNED_OUT -> "Not connected"
    FindMyPhase.SIGNING_IN -> "Signing in"
    FindMyPhase.NEEDS_TWO_FACTOR -> "Verify"
    FindMyPhase.REFRESHING -> "Refreshing"
    FindMyPhase.READY -> "Ready"
    FindMyPhase.SESSION_ERROR, FindMyPhase.ERROR -> "Attention"
}

private fun networkSourceSetupStatus(state: FindMyNetworkUiState): String = when (state.phase) {
    FindMyNetworkPhase.RESTORING -> "Restoring"
    FindMyNetworkPhase.SIGNED_OUT -> "Not connected"
    FindMyNetworkPhase.SIGNING_IN -> "Signing in"
    FindMyNetworkPhase.CHOOSE_TWO_FACTOR_METHOD,
    FindMyNetworkPhase.ENTER_TWO_FACTOR_CODE -> "Verify"
    FindMyNetworkPhase.READY_TO_RECOVER,
    FindMyNetworkPhase.OPENING_RECOVERY,
    FindMyNetworkPhase.CHOOSE_RECOVERY_DEVICE,
    FindMyNetworkPhase.UNLOCKING_KEYCHAIN,
    FindMyNetworkPhase.CHOOSE_ACCESSORIES,
    FindMyNetworkPhase.IMPORTING_ACCESSORIES -> "Recovery"
    FindMyNetworkPhase.REFRESHING_REPORTS -> "Refreshing"
    FindMyNetworkPhase.READY -> "Ready"
    FindMyNetworkPhase.ERROR -> "Attention"
}

@Composable
private fun FindMyNetworkContent(
    state: FindMyNetworkUiState,
    viewModel: FindMyNetworkViewModel,
    backdrop: LayerBackdrop,
) {
    var appleId by rememberSaveable(state.appleId) { mutableStateOf(state.appleId) }
    var password by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(4_000)
            viewModel.consumeMessage()
        }
    }

    state.message?.let { message ->
        StyledFloatingSurface(
            backdrop = backdrop,
            selected = true,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }

    when (state.phase) {
        FindMyNetworkPhase.RESTORING -> NetworkBusy("Restoring encrypted network session…")

        FindMyNetworkPhase.SIGNED_OUT -> {
            Text(
                text = "Sign in once to recover accessory owner keys directly from Apple, then decrypt their Find My network reports on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
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
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
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
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            StyledButton(
                onClick = {
                    val submitted = password
                    password = ""
                    viewModel.signIn(appleId, submitted)
                },
                backdrop = backdrop,
                enabled = appleId.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("Sign in to Find My network") }
            Text(
                text = "Security: reusable account state and recovered private keys are encrypted with a non-exportable Android Keystore key. Passwords, verification codes, and device passcodes are not stored separately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        FindMyNetworkPhase.SIGNING_IN -> NetworkBusy("Signing in securely with Apple…")

        FindMyNetworkPhase.CHOOSE_TWO_FACTOR_METHOD -> {
            Text(
                "Choose where Apple should send the verification code.",
                modifier = Modifier.padding(top = 12.dp),
            )
            state.twoFactorMethods.forEach { method ->
                StyledButton(
                    onClick = { viewModel.chooseTwoFactorMethod(method.index) },
                    backdrop = backdrop,
                    materialButtonStyle = MaterialButtonStyle.Outlined,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(method.label) }
            }
        }

        FindMyNetworkPhase.ENTER_TWO_FACTOR_CODE -> {
            Text(
                "Enter the six-digit Apple verification code.",
                modifier = Modifier.padding(top = 12.dp),
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
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            StyledButton(
                onClick = {
                    val submitted = verificationCode
                    verificationCode = ""
                    viewModel.submitTwoFactorCode(submitted)
                },
                backdrop = backdrop,
                enabled = verificationCode.length == 6,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Verify") }
        }

        FindMyNetworkPhase.READY_TO_RECOVER -> {
            Text(
                text = "Signed in as ${state.appleId}. Recover owner keys from your encrypted iCloud keychain using a trusted device and that device's screen-lock passcode.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            StyledButton(
                onClick = viewModel::openRecovery,
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("Recover accessory keys") }
        }

        FindMyNetworkPhase.OPENING_RECOVERY -> NetworkBusy("Opening Apple's keychain recovery…")

        FindMyNetworkPhase.CHOOSE_RECOVERY_DEVICE -> {
            Text(
                "Choose a trusted Apple device whose screen-lock passcode you know.",
                modifier = Modifier.padding(top = 12.dp),
            )
            state.recoveryDevices.forEach { device ->
                StyledButton(
                    onClick = { viewModel.selectRecoveryDevice(device.serial) },
                    backdrop = backdrop,
                    materialButtonStyle = MaterialButtonStyle.Outlined,
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
            StyledButton(
                onClick = viewModel::cancelRecovery,
                backdrop = backdrop,
                materialButtonStyle = MaterialButtonStyle.Normal,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel recovery") }
        }

        FindMyNetworkPhase.UNLOCKING_KEYCHAIN -> NetworkBusy("Unlocking the encrypted keychain…")

        FindMyNetworkPhase.CHOOSE_ACCESSORIES -> {
            Text(
                "Choose which recovered accessories to keep on this phone.",
                modifier = Modifier.padding(top = 12.dp),
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
            StyledButton(
                onClick = viewModel::importSelectedAccessories,
                backdrop = backdrop,
                enabled = state.selectedBeaconIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("Import ${state.selectedBeaconIds.size} selected") }
            StyledButton(
                onClick = viewModel::cancelRecovery,
                backdrop = backdrop,
                materialButtonStyle = MaterialButtonStyle.Normal,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }

        FindMyNetworkPhase.IMPORTING_ACCESSORIES ->
            NetworkBusy("Encrypting recovered accessory keys…")

        FindMyNetworkPhase.REFRESHING_REPORTS ->
            NetworkBusy("Downloading and decrypting Find My network reports…")

        FindMyNetworkPhase.READY -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StyledButton(
                    onClick = viewModel::refreshReports,
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f),
                ) { Text("Refresh") }
                StyledButton(
                    onClick = viewModel::openRecovery,
                    backdrop = backdrop,
                    materialButtonStyle = MaterialButtonStyle.Outlined,
                    modifier = Modifier.weight(1f),
                ) { Text("Recover more") }
            }
            state.lastUpdatedMillis?.let {
                Text(
                    "Network reports updated ${relativeTime(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        FindMyNetworkPhase.ERROR -> {
            Text(
                "The encrypted network session could not be restored. Retry the saved session before continuing with another Find My source.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            StyledButton(
                onClick = viewModel::retryRestore,
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("Retry encrypted session") }
        }
    }
}

@Composable
private fun NetworkBusy(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoadingFindMy(label: String, onOpenSourcesSetup: (() -> Unit)? = null) {
    val backdrop = rememberLayerBackdrop()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .layerBackdrop(backdrop),
        )
        StyledFloatingSurface(
            backdrop = backdrop,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(label, modifier = Modifier.padding(top = 12.dp), textAlign = TextAlign.Center)
                onOpenSourcesSetup?.let { openSetup ->
                    StyledButton(
                        onClick = openSetup,
                        backdrop = backdrop,
                        materialButtonStyle = MaterialButtonStyle.Outlined,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("Open Find My sources setup") }
                }
            }
        }
    }
}

@Composable
private fun FindMyFormContainer(
    content: @Composable ColumnScope.(backdrop: LayerBackdrop) -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    val topPadding = if (LocalDesignSystem.current == DesignSystem.Apple) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 80.dp
    } else {
        16.dp
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .layerBackdrop(backdrop),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = 32.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            content(this, backdrop)
        }
    }
}

private fun relativeTime(timestampMillis: Long): String = DateUtils.getRelativeTimeSpanString(
    timestampMillis,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
).toString()
