package de.yurtemre.sonora.ui.screens.update

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.BuildConfig
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.update.UpdateCheckResult
import de.yurtemre.sonora.ui.screens.changelog.MarkdownParser
import de.yurtemre.sonora.ui.screens.changelog.MdDocument
import de.yurtemre.sonora.ui.screens.changelog.SleeveMarkdown
import de.yurtemre.sonora.update.InstallError
import de.yurtemre.sonora.update.InstallResult
import de.yurtemre.sonora.update.UpdateUiState

/**
 * The update dialog (UPD-06/08): version, scrolling release notes, and Later / Install.
 *
 * An alert dialog instead of the old bottom sheet: it has a fixed size to grow into (the notes scroll, the status
 * area below has a reserved height), so nothing resizes or jumps while a download runs or the state changes. The
 * notes are parsed once into immutable blocks and the notes composable only sees that document, so progress updates
 * recompose the status line and nothing else.
 */
@Composable
fun UpdateDialog(
    update: UpdateCheckResult.Available,
    state: UpdateUiState,
    installSupported: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
) {
    val uri = LocalUriHandler.current
    val haptics = LocalHapticFeedback.current
    val releasePage = update.release.htmlUrl.ifBlank { releasesUrl() }
    val notes = remember(update.notesMarkdown) { MarkdownParser.parse(update.notesMarkdown) }
    val install = (state as? UpdateUiState.Install)?.takeIf { it.update == update }
    val openRelease = { onDismiss(); uri.openUri(releasePage) }
    val later = @Composable { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_action_later)) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_sheet_title)) },
        text = { UpdateBody(update, notes, install, installSupported, onBrowser = openRelease) },
        confirmButton = {
            when {
                !installSupported -> TextButton(onClick = openRelease) { Text(stringResource(R.string.update2_open_release)) }
                install == null || install is UpdateUiState.Installed -> TextButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onInstall()
                }) { Text(stringResource(R.string.update_action_install)) }
                install is UpdateUiState.NeedsPermission -> TextButton(onClick = onOpenPermissionSettings) {
                    Text(stringResource(R.string.update2_open_settings))
                }
                install is UpdateUiState.InstallFailed -> TextButton(onClick = onRetry) { Text(stringResource(R.string.update_action_retry)) }
                install is UpdateUiState.Downloading || install is UpdateUiState.Verifying -> TextButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    onCancel()
                }) { Text(stringResource(R.string.update2_cancel)) }
                else -> Unit // ready to install / installing: nothing to press, the system takes over
            }
        },
        dismissButton = {
            val running = install is UpdateUiState.Downloading || install is UpdateUiState.Verifying ||
                install is UpdateUiState.ReadyToInstall || install is UpdateUiState.Installing
            if (!running) later()
        },
    )
}

@Composable
private fun UpdateBody(
    update: UpdateCheckResult.Available,
    notes: MdDocument,
    install: UpdateUiState.Install?,
    installSupported: Boolean,
    onBrowser: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.update_sheet_version, update.version.toString()),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.update_sheet_current, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Takes whatever height the dialog has left after the status area; scrolls when the notes are longer.
        Column(Modifier.weight(1f, fill = false).padding(top = 12.dp).verticalScroll(rememberScrollState())) {
            if (notes.isEmpty) {
                Text(stringResource(R.string.update_sheet_no_notes), style = MaterialTheme.typography.bodyMedium)
            } else {
                SleeveMarkdown(notes)
            }
        }
        // Reserved height: the status never changes the dialog's size (except a failure with a long message).
        Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(top = 12.dp)) {
            UpdateStatus(install, installSupported, onBrowser)
        }
    }
}

@Composable
private fun UpdateStatus(
    install: UpdateUiState.Install?,
    installSupported: Boolean,
    onBrowser: () -> Unit,
) {
    val context = LocalContext.current
    val progressCd = stringResource(R.string.update2_cd_progress)
    val bar = Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = progressCd }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            !installSupported -> Text(
                stringResource(R.string.update2_dev_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            install == null || install is UpdateUiState.Installed -> BrowserButton(onBrowser)
            install is UpdateUiState.NeedsPermission -> {
                Text(stringResource(R.string.update2_permission_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.update2_permission_message), style = MaterialTheme.typography.bodyMedium)
            }
            install is UpdateUiState.Downloading -> {
                val done = Formatter.formatShortFileSize(context, install.bytes)
                Text(
                    if (install.total > 0) {
                        stringResource(R.string.update2_downloading, done, Formatter.formatShortFileSize(context, install.total))
                    } else stringResource(R.string.update2_downloading_unknown, done),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val f = install.fraction
                if (f != null) LinearWavyProgressIndicator(progress = { f }, trackColor = track, modifier = bar)
                else LinearWavyProgressIndicator(trackColor = track, modifier = bar)
            }
            install is UpdateUiState.Verifying || install is UpdateUiState.ReadyToInstall || install is UpdateUiState.Installing -> {
                Text(
                    stringResource(if (install is UpdateUiState.Verifying) R.string.update2_verifying else R.string.update2_installing),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearWavyProgressIndicator(trackColor = track, modifier = bar)
            }
            install is UpdateUiState.InstallFailed -> {
                Text(
                    installErrorText(install.error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (install.error == InstallError.Cancelled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
                BrowserButton(onBrowser)
            }
        }
    }
}

@Composable
private fun BrowserButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = ButtonDefaults.TextButtonContentPadding) {
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(end = ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.update_action_browser))
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
