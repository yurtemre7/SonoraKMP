package de.yurtemre.sonora.ui.screens.devicesync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.devicesync.DeviceSyncState
import de.yurtemre.sonora.devicesync.DiscoveredSender
import de.yurtemre.sonora.devicesync.SyncTarget
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsRow
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold
import de.yurtemre.sonora.ui.screens.settings.settingsHeader
import kotlinx.coroutines.delay

/** Time after which an empty sender list opens the manual address field (DSYNC-02 fallback). */
private const val SEARCH_TIMEOUT_MS = 10_000L

private enum class ConnectError { NeedSender, InvalidAddress }

/**
 * DSYNC-02/03, R3: discovered senders (the only one is auto-selected), the 6-digit code (auto-submits on the 6th
 * digit) and the manual `IP:port` field. [connecting] is the target while [DeviceSyncState.Connecting].
 */
@Composable
internal fun DeviceSyncConnectScreen(
    discovering: DeviceSyncState.Discovering?,
    connecting: SyncTarget?,
    onConnect: (SyncTarget, String) -> Unit,
    onBack: () -> Unit,
) {
    val senders = discovering?.senders.orEmpty()
    val discoveryFailed = discovering?.discoveryFailed == true
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var code by rememberSaveable { mutableStateOf("") }
    var manualText by rememberSaveable { mutableStateOf("") }
    var manualToggled by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var timedOut by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<ConnectError?>(null) }
    LaunchedEffect(Unit) {
        delay(SEARCH_TIMEOUT_MS)
        timedOut = true
    }
    val manualOpen = isManualOpen(manualToggled, manualText, discoveryFailed, senders.size, timedOut)
    val sender = effectiveSender(senders, selectedName)
    val busy = connecting != null

    fun submit(c: String = code) {
        if (c.length != CODE_LENGTH || busy) return
        when (val action = connectAction(manualOpen, manualText, sender)) {
            is ConnectAction.Connect -> { error = null; onConnect(action.target, c) }
            ConnectAction.InvalidAddress -> error = ConnectError.InvalidAddress
            ConnectAction.NeedSender -> error = ConnectError.NeedSender
        }
    }

    SettingsScaffold(stringResource(R.string.settings_devicesync_title), onBack) {
        if (!busy) {
            settingsHeader("senders") { stringResource(R.string.devicesync_senders_title) }
            if (senders.isEmpty()) {
                item(key = "searching") {
                    when {
                        discoveryFailed -> SettingsNote(stringResource(R.string.devicesync_discovery_failed))
                        timedOut -> SettingsNote(stringResource(R.string.devicesync_none_found))
                        else -> StatusProgress(stringResource(R.string.devicesync_searching), null)
                    }
                }
            }
            if (senders.isNotEmpty()) {
                item(key = "senders") {
                    Column(Modifier.selectableGroup()) {
                        senders.forEach { s ->
                            // A typed address is used instead of any sender (see connectAction).
                            SenderRow(s, selected = s == sender && manualText.isBlank()) {
                                selectedName = s.serviceName
                                manualText = ""
                                manualToggled = false
                                error = null
                            }
                        }
                    }
                }
            }
            item(key = "manual_toggle") {
                val expandState = stringResource(if (manualOpen) R.string.devicesync_expanded else R.string.devicesync_collapsed)
                SettingsRow(
                    stringResource(R.string.devicesync_manual_title),
                    Modifier.semantics { stateDescription = expandState },
                    icon = Icons.Rounded.Lan,
                    onClick = {
                        // Collapsing is an explicit choice to stop using the typed address.
                        if (manualOpen) manualText = ""
                        manualToggled = !manualOpen
                    },
                    trailing = { Icon(if (manualOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) },
                )
            }
            item(key = "manual") {
                AnimatedVisibility(manualOpen) {
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it.trim(); if (error == ConnectError.InvalidAddress) error = null },
                        label = { Text(stringResource(R.string.devicesync_manual_field)) },
                        singleLine = true,
                        isError = error == ConnectError.InvalidAddress,
                        supportingText = if (error == ConnectError.InvalidAddress) {
                            { Text(stringResource(R.string.devicesync_manual_invalid)) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
        item(key = "code") {
            val spoken = spokenDigits(code)
            OutlinedTextField(
                value = code,
                onValueChange = {
                    val digits = codeInput(it)
                    val completed = digits.length == CODE_LENGTH && code.length != CODE_LENGTH
                    code = digits
                    if (error == ConnectError.NeedSender) error = null
                    if (completed) submit(digits)
                },
                label = { Text(stringResource(R.string.devicesync_code_field)) },
                singleLine = true,
                enabled = !busy,
                isError = error == ConnectError.NeedSender,
                supportingText = if (error == ConnectError.NeedSender) {
                    { Text(stringResource(R.string.devicesync_choose_sender)) }
                } else null,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 28.sp, letterSpacing = 6.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { stateDescription = spoken },
            )
        }
        item(key = "connect") {
            if (connecting != null) {
                StatusProgress(stringResource(R.string.devicesync_connecting, connecting.name ?: stringResource(R.string.devicesync_address, connecting.host, connecting.port)), null)
            } else {
                ActionsRow {
                    Button(onClick = { submit() }, enabled = code.length == CODE_LENGTH, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.devicesync_connect))
                    }
                }
            }
        }
    }
}

@Composable
private fun SenderRow(sender: DiscoveredSender, selected: Boolean, onSelect: () -> Unit) {
    SettingsRow(
        sender.name,
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        icon = Icons.Rounded.Smartphone,
        subtitle = stringResource(R.string.devicesync_address, sender.host, sender.port),
        trailing = { RadioButton(selected = selected, onClick = null) },
    )
}
