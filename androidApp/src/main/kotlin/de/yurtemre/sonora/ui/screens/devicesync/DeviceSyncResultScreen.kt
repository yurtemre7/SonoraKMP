package de.yurtemre.sonora.ui.screens.devicesync

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.devicesync.DeviceSyncState
import de.yurtemre.sonora.devicesync.DeviceSyncSummary
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsRow
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold

/** DSYNC-11..13 result: counts, failed files (expandable), received data, rescan hint; Done. */
@Composable
internal fun DeviceSyncResultScreen(summary: DeviceSyncSummary, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var showFailed by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(stringResource(R.string.settings_devicesync_title), onBack) {
        item(key = "title") {
            SettingsRow(stringResource(R.string.devicesync_done_title), icon = Icons.Rounded.CheckCircle)
        }
        item(key = "files") {
            val lines = buildList {
                add(pluralStringResource(R.plurals.devicesync_result_copied, summary.filesCopied, summary.filesCopied))
                if (summary.filesReplaced > 0) {
                    add(pluralStringResource(R.plurals.devicesync_result_replaced, summary.filesReplaced, summary.filesReplaced))
                }
                if (summary.filesKeptBoth > 0) {
                    add(pluralStringResource(R.plurals.devicesync_result_kept_both, summary.filesKeptBoth, summary.filesKeptBoth))
                }
                if (summary.filesRemoved > 0) {
                    add(pluralStringResource(R.plurals.devicesync_result_removed, summary.filesRemoved, summary.filesRemoved))
                }
                if (summary.bytes > 0) add(stringResource(R.string.devicesync_result_bytes, formatBytes(context, summary.bytes)))
            }
            SettingsNote(lines.joinToString("\n"))
        }
        item(key = "data") {
            SettingsNote(
                listOf(
                    pluralStringResource(R.plurals.devicesync_result_playlists, summary.playlists, summary.playlists),
                    pluralStringResource(R.plurals.devicesync_result_favorites, summary.favorites, summary.favorites),
                    pluralStringResource(R.plurals.devicesync_result_settings, summary.settings, summary.settings),
                    stringResource(
                        R.string.devicesync_join,
                        pluralStringResource(R.plurals.devicesync_result_stats_songs, summary.statsSongsAdded, summary.statsSongsAdded),
                        pluralStringResource(R.plurals.devicesync_result_listens, summary.completeListensAdded, summary.completeListensAdded),
                    ),
                ).joinToString("\n"),
            )
        }
        if (summary.failed.isNotEmpty()) {
            item(key = "failed") {
                val expandState = stringResource(if (showFailed) R.string.devicesync_expanded else R.string.devicesync_collapsed)
                SettingsRow(
                    pluralStringResource(R.plurals.devicesync_result_failed, summary.failed.size, summary.failed.size),
                    icon = Icons.Rounded.ErrorOutline,
                    subtitle = stringResource(if (showFailed) R.string.devicesync_hide_failed else R.string.devicesync_show_failed),
                    onClick = { showFailed = !showFailed },
                    modifier = Modifier.semantics { stateDescription = expandState },
                    trailing = { Icon(if (showFailed) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) },
                )
            }
            if (showFailed) {
                summary.failed.forEachIndexed { i, f ->
                    item(key = "failed_$i") {
                        SettingsRow(f.relPath, subtitle = stringResource(failedFileReason(f.reason)))
                    }
                }
            }
        }
        if (summary.rescanSkipped) {
            item(key = "rescan") {
                SettingsRow(stringResource(R.string.devicesync_result_rescan_skipped), icon = Icons.Outlined.Info)
            }
        }
        item(key = "done") {
            ActionsRow {
                Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.devicesync_done)) }
            }
        }
    }
}

/** A stopped device sync: localized reason, files kept, Retry (when possible) and Back. */
@Composable
internal fun DeviceSyncFailedScreen(state: DeviceSyncState.Failed, onRetry: () -> Unit, onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.settings_devicesync_title), onBack) {
        item(key = "failed") {
            EmptyState(
                Icons.Rounded.ErrorOutline,
                stringResource(R.string.devicesync_failed_title),
                subtitle = stringResource(failureMessage(state.reason)),
            )
        }
        if (state.filesKept > 0) {
            item(key = "kept") {
                SettingsNote(pluralStringResource(R.plurals.devicesync_failure_kept, state.filesKept, state.filesKept))
            }
        }
        item(key = "actions") {
            ActionsRow {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.back)) }
                if (state.canRetry) {
                    Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.devicesync_retry)) }
                }
            }
        }
    }
}
