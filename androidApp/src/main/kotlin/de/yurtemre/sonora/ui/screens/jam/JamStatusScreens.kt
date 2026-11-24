package de.yurtemre.sonora.ui.screens.jam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.jam.JamUiState
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.screens.devicesync.ActionsRow
import de.yurtemre.sonora.ui.screens.devicesync.ChoiceCard
import de.yurtemre.sonora.ui.screens.devicesync.StatusProgress
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold

/** Pick a role (Settings › Jam), like the device sync start screen; [starting] while the server starts. */
@Composable
internal fun JamStartScreen(starting: Boolean, onStart: () -> Unit, onJoin: () -> Unit, onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.jam_title), onBack) {
        if (starting) {
            item(key = "starting") { StatusProgress(stringResource(R.string.jam_starting), null) }
            return@SettingsScaffold
        }
        item(key = "intro") { SettingsNote(stringResource(R.string.jam_how_it_works)) }
        item(key = "start") {
            ChoiceCard(Icons.Rounded.PlayCircle, stringResource(R.string.jam_start), stringResource(R.string.jam_start_body), onStart)
        }
        item(key = "join") {
            ChoiceCard(Icons.Rounded.Groups, stringResource(R.string.jam_join), stringResource(R.string.jam_join_body), onJoin)
        }
    }
}

/** A guest in a Jam: who hosts, how it is going, and Leave. The player itself is Now Playing. */
@Composable
internal fun JamGuestScreen(state: JamUiState.Joined, onOpenPlayer: () -> Unit, onLeave: () -> Unit, onBack: () -> Unit) {
    val status = jamPlayerUi(state).status
    SettingsScaffold(stringResource(R.string.jam_title), onBack) {
        item(key = "with") {
            EmptyState(
                Icons.Rounded.Groups,
                stringResource(R.string.jam_with, state.hostName),
                Modifier.fillMaxWidth(),
                subtitle = status?.let { stringResource(jamStatusText(it)) },
            )
        }
        item(key = "body") { SettingsNote(stringResource(R.string.jam_guest_body)) }
        if (!state.canControl) {
            item(key = "host_only") { SettingsNote(stringResource(R.string.jam_guest_controls_host_only)) }
        }
        item(key = "actions") {
            ActionsRow {
                OutlinedButton(onClick = onLeave, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.jam_leave_jam))
                }
                Button(onClick = onOpenPlayer, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.jam_open))
                }
            }
        }
    }
}

/** "Jam ended" with the reason; Rejoin after a Wi-Fi change; Done. */
@Composable
internal fun JamEndedScreen(state: JamUiState.Ended, onRejoin: () -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.jam_title), onBack) {
        item(key = "ended") {
            EmptyState(
                Icons.Rounded.MusicOff,
                stringResource(R.string.jam_ended_title),
                Modifier.fillMaxWidth(),
                subtitle = stringResource(jamEndMessage(state.reason)) + " " + stringResource(R.string.jam_ended_queue_back),
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.rejoin != null) {
                            OutlinedButton(onClick = onRejoin, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.jam_rejoin))
                            }
                        }
                        Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.jam_done))
                        }
                    }
                },
            )
        }
    }
}

/** The server could not start (no Wi-Fi, no free port). */
@Composable
internal fun JamHostFailedScreen(onRetry: () -> Unit, onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.jam_title), onBack) {
        item(key = "failed") {
            EmptyState(
                Icons.Rounded.WifiOff,
                stringResource(R.string.jam_host_failed_title),
                Modifier.fillMaxWidth(),
                subtitle = stringResource(R.string.jam_host_failed_body),
                action = {
                    Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.jam_try_again))
                    }
                },
            )
        }
    }
}
