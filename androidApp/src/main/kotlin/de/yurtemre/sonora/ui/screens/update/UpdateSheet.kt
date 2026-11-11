package de.yurtemre.sonora.ui.screens.update

import android.widget.Toast
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
import kotlinx.coroutines.launch

/** Update sheet (UPD-06): version, release-notes markdown (≤ 60 % height), Later / Open in browser / Download. */
@Composable
fun UpdateSheet(
    update: UpdateCheckResult.Available,
    installSupported: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val maxNotes = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val pendingMsg = stringResource(R.string.update_download_pending)
    fun hide(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }
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
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { hide(onDismiss) }) { Text(stringResource(R.string.update_action_later)) }
                OutlinedButton(onClick = { hide { onDismiss(); uri.openUri(update.release.htmlUrl.ifBlank { releasesUrl() }) } }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(end = ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.update_action_browser))
                }
                Button(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    if (installSupported) {
                        hide(onDownload)
                    } else {
                        Toast.makeText(context, pendingMsg, Toast.LENGTH_SHORT).show()
                        hide { onDismiss(); uri.openUri(update.release.htmlUrl.ifBlank { releasesUrl() }) }
                    }
                }) {
                    Icon(Icons.Rounded.Download, null, Modifier.padding(end = ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.update_action_download))
                }
            }
        }
    }
}

internal fun releasesUrl(): String = de.yurtemre.sonora.update.UpdateViewModel.releasesUrl
