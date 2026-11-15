package de.yurtemre.sonora.ui.screens.devicesync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.devicesync.DeviceSyncState
import de.yurtemre.sonora.shared.devicesync.ServerState
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/** DSYNC-01/03: the code (3+3, monospace) with its countdown, pairing status and the manual address; Stop. */
@Composable
internal fun DeviceSyncSenderScreen(state: DeviceSyncState.Sending, onStop: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val server = state.server
    SettingsScaffold(stringResource(R.string.settings_devicesync_title), onBack) {
        if (server is ServerState.Waiting || server is ServerState.Failed) {
            item(key = "code") { CodeCard(state.code, state.codeExpiresAtMs) }
            if (server is ServerState.Failed) {
                item(key = "mismatch") { ErrorNote(stringResource(R.string.devicesync_sender_code_mismatch)) }
            }
            item(key = "instructions") { SettingsNote(stringResource(R.string.devicesync_sender_instructions)) }
        }
        item(key = "status") {
            when (server) {
                is ServerState.Paired -> StatusProgress(stringResource(R.string.devicesync_sender_paired, server.peerName), null)
                is ServerState.Sending -> StatusProgress(
                    stringResource(
                        R.string.devicesync_sender_sending,
                        formatBytes(context, server.bytesSent),
                        formatBytes(context, server.totalBytes),
                    ),
                    if (server.totalBytes > 0) (server.bytesSent.toFloat() / server.totalBytes).coerceIn(0f, 1f) else null,
                )
                else -> StatusProgress(stringResource(R.string.devicesync_sender_waiting), null)
            }
        }
        state.address?.let { address ->
            item(key = "address") { SettingsNote(stringResource(R.string.devicesync_sender_address, address)) }
        }
        item(key = "stop") {
            ActionsRow {
                OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.devicesync_stop))
                }
            }
        }
    }
}

@Composable
private fun CodeCard(code: String, expiresAtMs: Long) {
    val clock: Clock = koinInject()
    val now by produceState(clock.nowMs(), expiresAtMs) {
        while (true) {
            value = clock.nowMs()
            delay(1_000)
        }
    }
    val cs = MaterialTheme.colorScheme
    val spoken = stringResource(R.string.devicesync_code_a11y, spokenDigits(code))
    DeviceSyncCard(color = cs.primaryContainer) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.devicesync_code_title),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onPrimaryContainer,
            )
            Text(
                groupCode(code),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 4.sp,
                ),
                color = cs.onPrimaryContainer,
                modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
            )
            Text(
                stringResource(R.string.devicesync_code_expires, countdownText(expiresAtMs, now)),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onPrimaryContainer,
            )
        }
    }
}

/** The receiver finished and sent DONE (Finished(null) on the sender). */
@Composable
internal fun DeviceSyncSentScreen(onDone: () -> Unit, onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.settings_devicesync_title), onBack) {
        item(key = "sent") {
            EmptyState(
                Icons.Rounded.CheckCircle,
                stringResource(R.string.devicesync_sent_title),
                Modifier.fillMaxWidth(),
                subtitle = stringResource(R.string.devicesync_sent_body),
                action = {
                    Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.devicesync_done))
                    }
                },
            )
        }
    }
}
