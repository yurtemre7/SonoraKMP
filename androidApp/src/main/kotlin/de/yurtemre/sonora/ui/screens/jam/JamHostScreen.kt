package de.yurtemre.sonora.ui.screens.jam

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.devicesync.DeviceSyncDiscovery
import de.yurtemre.sonora.jam.JamGuestUi
import de.yurtemre.sonora.jam.JamUiState
import de.yurtemre.sonora.ui.screens.devicesync.ActionsRow
import de.yurtemre.sonora.ui.screens.devicesync.DeviceSyncCard
import de.yurtemre.sonora.ui.screens.devicesync.ErrorNote
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsRow
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold
import de.yurtemre.sonora.ui.screens.settings.settingsHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The host's Jam: the code in large type (3+3, read digit by digit), how guests join (with this phone's address for
 * networks that hide it), the guests with a remove action each, live permissions, a new code (prominent once the
 * code is blocked) and End Jam. The screen may time out normally: the Jam runs in its foreground service.
 */
@Composable
internal fun JamHostScreen(
    state: JamUiState.Hosting,
    onKick: (JamGuestUi) -> Unit,
    onCanAdd: (Boolean) -> Unit,
    onCanControl: (Boolean) -> Unit,
    onNewCode: () -> Unit,
    onEnd: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val ip by produceState<String?>(null) { value = withContext(Dispatchers.IO) { DeviceSyncDiscovery(context).localIpv4() } }
    SettingsScaffold(stringResource(R.string.jam_title), onBack) {
        item(key = "code") { JamCodeCard(state.code) }
        if (state.codeExhausted) {
            item(key = "blocked") { ErrorNote(stringResource(R.string.jam_host_code_blocked)) }
            item(key = "new_code_prominent") {
                ActionsRow {
                    Button(onClick = onNewCode, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Refresh, null)
                        Text(stringResource(R.string.jam_new_code))
                    }
                }
            }
        }
        item(key = "hint") { SettingsNote(stringResource(R.string.jam_host_hint)) }
        ip?.let { address ->
            item(key = "address") { SettingsNote(stringResource(R.string.jam_host_address, "$address:${state.port}")) }
        }
        if (state.hostSpeedEffectActive) {
            item(key = "speed") { ErrorNote(stringResource(R.string.jam_speed_note)) }
        }

        settingsHeader("guests") {
            stringResource(R.string.jam_guests_title) + "  " +
                pluralStringResource(R.plurals.jam_guests_count, state.guests.size, state.guests.size)
        }
        if (state.guests.isEmpty()) {
            item(key = "no_guests") { SettingsNote(stringResource(R.string.jam_no_guests)) }
        }
        state.guests.forEach { guest ->
            item(key = "guest_${guest.id}") {
                SettingsRow(
                    guest.name,
                    icon = Icons.Rounded.Smartphone,
                    trailing = {
                        IconButton(onClick = { onKick(guest) }) {
                            Icon(Icons.Rounded.PersonRemove, stringResource(R.string.jam_remove_guest, guest.name))
                        }
                    },
                )
            }
        }

        settingsHeader("permissions") { stringResource(R.string.jam_permissions_title) }
        item(key = "permissions") {
            JamPermissionRows(state.settings.guestsCanAdd, onCanAdd, state.settings.guestsCanControl, onCanControl)
        }

        item(key = "actions") {
            ActionsRow {
                if (!state.codeExhausted) {
                    TextButton(onClick = onNewCode, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.jam_new_code))
                    }
                }
                OutlinedButton(
                    onClick = onEnd,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.jam_end)) }
            }
        }
    }
}

/** The code, large and monospaced in groups of three; TalkBack reads "Jam code 1 2 3 4 5 6". */
@Composable
private fun JamCodeCard(code: String) {
    val cs = MaterialTheme.colorScheme
    val spoken = stringResource(R.string.jam_code_a11y, spokenJamCode(code))
    DeviceSyncCard(color = cs.primaryContainer) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.jam_code_title), style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
            Text(
                formatJamCode(code),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 4.sp,
                ),
                textAlign = TextAlign.Center,
                color = cs.onPrimaryContainer,
                modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
            )
        }
    }
}
