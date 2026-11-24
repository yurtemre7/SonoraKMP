package de.yurtemre.sonora.ui.screens.jam

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
import de.yurtemre.sonora.ui.format.DurationFormat
import de.yurtemre.sonora.ui.screens.devicesync.ActionsRow
import de.yurtemre.sonora.ui.screens.devicesync.ErrorNote
import de.yurtemre.sonora.ui.screens.devicesync.StatusProgress
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsRow
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold
import de.yurtemre.sonora.ui.screens.settings.settingsHeader

/** Callbacks of the join screen (all go to [JamViewModel]). */
internal class JamJoinActions(
    val onSelect: (String) -> Unit,
    val onManualText: (String) -> Unit,
    val onToggleManual: () -> Unit,
    val onCode: (String) -> Unit,
    val onSubmit: () -> Unit,
)

/**
 * Join a Jam, the device sync way: Jams found on the Wi-Fi (with the host's name; one is selected automatically),
 * the manual host:port field (open by default when nothing is found or discovery fails), and the 6-digit code that
 * joins on its 6th digit. Errors show in plain words with what to do next; a lockout counts down.
 */
@Composable
internal fun JamJoinScreen(ui: JamJoinUi, actions: JamJoinActions, onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.jam_join), onBack) {
        if (ui.rejoinName != null) {
            item(key = "rejoin") { SettingsNote(stringResource(R.string.jam_rejoin_target, ui.rejoinName)) }
        } else if (!ui.joining) {
            discoveredSection(ui, actions)
            manualSection(ui, actions)
        }
        ui.error?.let { error ->
            item(key = "error") {
                val text = if (error == de.yurtemre.sonora.shared.jam.JamError.Locked && ui.lockSecondsLeft > 0) {
                    stringResource(R.string.jam_error_locked, DurationFormat.mss(ui.lockSecondsLeft.toLong()))
                } else stringResource(jamErrorMessage(error))
                ErrorNote(text)
            }
        }
        item(key = "code") {
            val spoken = spokenJamCode(ui.code)
            OutlinedTextField(
                value = ui.code,
                onValueChange = actions.onCode,
                label = { Text(stringResource(R.string.jam_code_field)) },
                singleLine = true,
                enabled = !ui.joining && ui.lockSecondsLeft == 0,
                isError = ui.needJam,
                supportingText = if (ui.needJam) {
                    { Text(stringResource(R.string.jam_choose)) }
                } else null,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 28.sp, letterSpacing = 6.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { actions.onSubmit() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { stateDescription = spoken },
            )
        }
        item(key = "join") {
            if (ui.joining) {
                StatusProgress(stringResource(R.string.jam_joining, ui.joiningName.orEmpty()), null)
            } else {
                ActionsRow {
                    Button(onClick = actions.onSubmit, enabled = ui.canSubmit, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.jam_join_button))
                    }
                }
            }
        }
        item(key = "how") { SettingsNote(stringResource(R.string.jam_how_it_works)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.discoveredSection(ui: JamJoinUi, actions: JamJoinActions) {
    settingsHeader("found") { stringResource(R.string.jam_found_title) }
    if (ui.jams.isEmpty()) {
        item(key = "searching") {
            when {
                ui.discoveryFailed -> SettingsNote(stringResource(R.string.jam_discovery_failed))
                ui.searchTimedOut -> SettingsNote(stringResource(R.string.jam_none_found))
                else -> StatusProgress(stringResource(R.string.jam_searching), null)
            }
        }
    } else {
        item(key = "jams") {
            Column(Modifier.selectableGroup()) {
                ui.jams.forEach { item ->
                    // A typed address is used instead of any found Jam.
                    val selected = item.jam.serviceName == ui.selected && ui.manualText.isBlank()
                    SettingsRow(
                        item.jam.hostName,
                        Modifier.selectable(
                            selected = selected,
                            enabled = item.compatible,
                            role = Role.RadioButton,
                            onClick = { actions.onSelect(item.jam.serviceName) },
                        ),
                        icon = Icons.Rounded.Smartphone,
                        subtitle = if (item.compatible) null else stringResource(R.string.jam_other_version),
                        enabled = item.compatible,
                        trailing = { RadioButton(selected = selected, onClick = null, enabled = item.compatible) },
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.manualSection(ui: JamJoinUi, actions: JamJoinActions) {
    item(key = "manual_toggle") {
        val expandState = stringResource(if (ui.manualOpen) R.string.devicesync_expanded else R.string.devicesync_collapsed)
        SettingsRow(
            stringResource(R.string.jam_manual_title),
            Modifier.semantics { stateDescription = expandState },
            icon = Icons.Rounded.Lan,
            onClick = actions.onToggleManual,
            trailing = { Icon(if (ui.manualOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) },
        )
    }
    item(key = "manual") {
        AnimatedVisibility(ui.manualOpen) {
            Column {
                OutlinedTextField(
                    value = ui.manualText,
                    onValueChange = actions.onManualText,
                    label = { Text(stringResource(R.string.jam_manual_field)) },
                    singleLine = true,
                    isError = ui.addressInvalid,
                    supportingText = if (ui.addressInvalid) {
                        { Text(stringResource(R.string.jam_manual_invalid)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                SettingsNote(stringResource(R.string.jam_manual_hint))
            }
        }
    }
}
