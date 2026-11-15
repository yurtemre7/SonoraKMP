package de.yurtemre.sonora.ui.screens.devicesync

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.devicesync.ApplyStep
import de.yurtemre.sonora.devicesync.DeviceSyncState
import de.yurtemre.sonora.ui.format.DurationFormat
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold

/** DSYNC-13: files, bytes, speed and ETA with Cancel; then the apply step text. */
@Composable
internal fun DeviceSyncProgressScreen(state: DeviceSyncState, onCancel: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_devicesync_title), onBack) {
        item(key = "title") { SettingsNote(stringResource(R.string.devicesync_receiving)) }
        when (state) {
            is DeviceSyncState.Transferring -> {
                val p = state.progress
                item(key = "progress") {
                    StatusProgress(
                        pluralStringResource(R.plurals.devicesync_progress_files, p.filesTotal, p.filesDone, p.filesTotal),
                        progressFraction(p),
                    )
                }
                item(key = "bytes") {
                    SettingsNote(
                        stringResource(R.string.devicesync_progress_bytes, formatBytes(context, p.totalBytes), formatBytes(context, p.totalSize)),
                    )
                }
                etaSeconds(p)?.let { eta ->
                    item(key = "speed") {
                        SettingsNote(
                            stringResource(R.string.devicesync_progress_speed, formatBytes(context, p.bytesPerSecond), DurationFormat.clock(eta)),
                        )
                    }
                }
                item(key = "cancel") {
                    ActionsRow {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
            is DeviceSyncState.Applying -> item(key = "step") {
                StatusProgress(
                    stringResource(
                        when (state.step) {
                            ApplyStep.LibrarySync -> R.string.devicesync_step_library
                            ApplyStep.Replacing -> R.string.devicesync_step_replacing
                            ApplyStep.Data -> R.string.devicesync_step_data
                        },
                    ),
                    null,
                )
            }
            else -> Unit
        }
    }
}
