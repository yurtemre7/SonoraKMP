package de.yurtemre.sonora.ui.screens.update

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.BuildConfig
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.update.UpdateCheckResult
import de.yurtemre.sonora.ui.screens.changelog.SleeveMarkdown
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import de.yurtemre.sonora.update.InstallError
import de.yurtemre.sonora.update.InstallResult
import de.yurtemre.sonora.update.UpdateUiState
import androidx.compose.animation.animateContentSize
import kotlinx.coroutines.launch

/**
 * Update sheet (UPD-06/08): version, release-notes markdown (≤ 60 % height), then Later / Open in browser / Download,
 * or the progress of the running install attempt (cancel, retry, permission and error messages).
 */
@Composable
fun UpdateSheet(
    update: UpdateCheckResult.Available,
    state: UpdateUiState,
    installSupported: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val haptics = LocalHapticFeedback.current
    val maxNotes = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val releasePage = update.release.htmlUrl.ifBlank { releasesUrl() }
    fun hide(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }
    val install = (state as? UpdateUiState.Install)?.takeIf { it.update == update }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.update_sheet_title), style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(
                stringResource(R.string.update_sheet_version, update.version.toString()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(R.string.update_sheet_current, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                Modifier.padding(vertical = 16.dp).fillMaxWidth().heightIn(max = maxNotes).verticalScroll(rememberScrollState()),
            ) {
                if (update.notesMarkdown.isBlank()) {
                    Text(stringResource(R.string.update_sheet_no_notes), style = MaterialTheme.typography.bodyMedium)
                } else {
                    SleeveMarkdown(update.notesMarkdown)
                }
            }
            run {
                Column(Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())) {
                    when {
                        !installSupported -> {
                            Text(
                                stringResource(R.string.update2_dev_disabled),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                            Actions {
                                TextButton(onClick = { hide(onDismiss) }) { Text(stringResource(R.string.update_action_later)) }
                                Button(onClick = { hide { onDismiss(); uri.openUri(releasePage) } }) {
                                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(end = ButtonDefaults.IconSpacing))
                                    Text(stringResource(R.string.update2_open_release))
                                }
                            }
                        }
                        install == null || install is UpdateUiState.Installed -> Actions {
                            TextButton(onClick = { hide(onDismiss) }) { Text(stringResource(R.string.update_action_later)) }
                            OutlinedButton(onClick = { hide { onDismiss(); uri.openUri(releasePage) } }) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(end = ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.update_action_browser))
                            }
                            Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onDownload() }) {
                                Icon(Icons.Rounded.Download, null, Modifier.padding(end = ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.update_action_download))
                            }
                        }
                        else -> InstallProgress(state, onDismiss = { hide(onDismiss) }, onCancel = {
                            haptics.performHapticFeedback(HapticFeedbackType.Reject); onCancel()
                        }, onRetry = onRetry, onOpenPermissionSettings = onOpenPermissionSettings, onBrowser = {
                            hide { onDismiss(); uri.openUri(releasePage) }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun InstallProgress(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onBrowser: () -> Unit,
) {
    val context = LocalContext.current
    val progressCd = stringResource(R.string.update2_cd_progress)
    val barModifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { contentDescription = progressCd }
    when (state) {
        is UpdateUiState.NeedsPermission -> {
            Text(stringResource(R.string.update2_permission_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.update2_permission_message),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Actions {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_action_later)) }
                Button(onClick = onOpenPermissionSettings) {
                    Icon(Icons.Rounded.Settings, null, Modifier.padding(end = ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.update2_open_settings))
                }
            }
        }
        is UpdateUiState.Downloading -> {
            val done = Formatter.formatShortFileSize(context, state.bytes)
            Text(
                if (state.total > 0) {
                    stringResource(R.string.update2_downloading, done, Formatter.formatShortFileSize(context, state.total))
                } else stringResource(R.string.update2_downloading_unknown, done),
                style = MaterialTheme.typography.bodyMedium,
            )
            val f = state.fraction
            if (f != null) LinearWavyProgressIndicator(trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, progress = { f }, modifier = barModifier)
            else LinearWavyProgressIndicator(trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = barModifier)
            Actions {
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Rounded.Close, null, Modifier.padding(end = ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.update2_cancel))
                }
            }
        }
        is UpdateUiState.Verifying, is UpdateUiState.ReadyToInstall, is UpdateUiState.Installing -> {
            Text(
                stringResource(if (state is UpdateUiState.Verifying) R.string.update2_verifying else R.string.update2_installing),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearWavyProgressIndicator(trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = barModifier)
            if (state is UpdateUiState.Verifying) Actions {
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.update2_cancel)) }
            }
        }
        is UpdateUiState.InstallFailed -> {
            Text(
                installErrorText(state.error),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.error == InstallError.Cancelled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Actions {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_action_later)) }
                OutlinedButton(onClick = onBrowser) { Text(stringResource(R.string.update_action_browser)) }
                Button(onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.padding(end = ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.update_action_retry))
                }
            }
        }
        else -> Unit
    }
}

@Composable
internal fun installErrorText(error: InstallError): String = when (error) {
    InstallError.NoNetwork -> stringResource(R.string.update2_err_no_network)
    is InstallError.Http -> stringResource(R.string.update2_err_http, error.code)
    InstallError.NoAsset -> stringResource(R.string.update2_err_no_asset)
    InstallError.ChecksumMismatch -> stringResource(R.string.update2_err_checksum)
    InstallError.Cancelled -> stringResource(R.string.update2_err_cancelled)
    is InstallError.InstallFailed -> when (val r = error.result) {
        InstallResult.Conflict -> stringResource(R.string.update2_err_conflict)
        InstallResult.Incompatible -> stringResource(R.string.update2_err_incompatible)
        InstallResult.Storage -> stringResource(R.string.update2_err_storage)
        is InstallResult.Other -> stringResource(R.string.update2_err_install, r.status)
        InstallResult.Success, InstallResult.Cancelled -> stringResource(R.string.update2_err_cancelled)
    }
}

internal fun releasesUrl(): String = de.yurtemre.sonora.update.UpdateViewModel.releasesUrl
