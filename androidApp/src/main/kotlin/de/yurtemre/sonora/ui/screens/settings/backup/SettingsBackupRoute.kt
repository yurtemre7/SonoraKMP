package de.yurtemre.sonora.ui.screens.settings.backup

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.locale.AppLocales
import de.yurtemre.sonora.shared.repo.api.BackupExportResult
import de.yurtemre.sonora.shared.repo.api.BackupRepository
import de.yurtemre.sonora.shared.repo.api.BackupRestoreResult
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.components.ConfirmDialog
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsRow
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.LocalDate

private enum class BackupBusy { Exporting, Restoring }

/** SET-06 / BAK-06: export via CreateDocument, import via OpenDocument → confirm → restore; 6 s result snackbars. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsBackupRoute(onBack: () -> Unit) {
    val backup: BackupRepository = koinInject()
    val settingsRepo: SettingsRepository = koinInject()
    val context = LocalContext.current
    val contextRes = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf<BackupBusy?>(null) }
    var pendingRestore by rememberSaveable { mutableStateOf<String?>(null) }

    fun show(message: String) = scope.showLong(snackbar, message)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = BackupBusy.Exporting
        scope.launch {
            val msg = when (val r = backup.export(uri.toString())) {
                BackupExportResult.Success -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    contextRes.getString(R.string.backup_export_success)
                }
                BackupExportResult.LibraryEmpty -> contextRes.getString(R.string.backup_error_library_empty)
                is BackupExportResult.WriteFailed -> contextRes.getString(R.string.backup_export_failed)
            }
            busy = null
            show(msg)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) pendingRestore = uri.toString()
    }

    fun launchSafely(block: () -> Unit) {
        try { block() } catch (_: ActivityNotFoundException) { show(contextRes.getString(R.string.backup_error_file_picker)) }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_backup_title),
        onBack = onBack,
        snackbar = snackbar,
        topBarBottom = {
            AnimatedVisibility(busy != null) {
                Column {
                    LinearWavyProgressIndicator(trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Text(
                        stringResource(
                            if (busy == BackupBusy.Restoring) R.string.settings_backup_restoring else R.string.settings_backup_exporting,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
    ) {
        item(key = "export") {
            SettingsRow(
                stringResource(R.string.settings_backup_export_title), icon = Icons.Rounded.UploadFile,
                subtitle = stringResource(R.string.settings_backup_export_body), enabled = busy == null, chevron = true,
                onClick = { launchSafely { exportLauncher.launch(defaultFileName()) } },
            )
        }
        item(key = "import") {
            SettingsRow(
                stringResource(R.string.settings_backup_import_title), icon = Icons.Rounded.SettingsBackupRestore,
                subtitle = stringResource(R.string.settings_backup_import_body), enabled = busy == null, chevron = true,
                onClick = { launchSafely { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) } },
            )
        }
        item(key = "contents") {
            SettingsRow(
                stringResource(R.string.settings_backup_contents_title),
                icon = Icons.Outlined.Info,
            )
        }
        item(key = "contents_body") { SettingsNote(stringResource(R.string.backup_contents_content)) }
    }

    pendingRestore?.let { uri ->
        ConfirmDialog(
            title = stringResource(R.string.settings_backup_confirm_title),
            message = stringResource(R.string.settings_backup_confirm_body),
            confirmLabel = stringResource(R.string.backup_restore_action),
            onDismiss = { pendingRestore = null },
            onConfirm = {
                pendingRestore = null
                busy = BackupBusy.Restoring
                scope.launch {
                    val result = backup.restore(uri)
                    if (result is BackupRestoreResult.Success) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (result.localeChanged) AppLocales.apply(settingsRepo.settings.first().appLocale)
                    }
                    busy = null
                    show(restoreMessage(context, result))
                }
            },
        )
    }
}

private fun CoroutineScope.showLong(host: SnackbarHostState, message: String) {
    launch {
        host.currentSnackbarData?.dismiss()
        val job = launch { host.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Indefinite) }
        delay(6_000)
        if (host.currentSnackbarData?.visuals?.message == message) host.currentSnackbarData?.dismiss()
        job.join()
    }
}

private fun defaultFileName(): String = "sonora-backup-${LocalDate.now()}.json"

private fun restoreMessage(context: Context, r: BackupRestoreResult): String = when (r) {
    is BackupRestoreResult.Success -> buildList {
        add(context.getString(R.string.backup_restore_success))
        add(context.resources.getQuantityString(R.plurals.playlist_count, r.playlists, r.playlists))
        add(context.resources.getQuantityString(R.plurals.backup_restored_favorites, r.favorites, r.favorites))
        if (r.unmatchedSongs > 0) {
            add(context.resources.getQuantityString(R.plurals.backup_unmatched_songs, r.unmatchedSongs, r.unmatchedSongs))
        }
        if (r.localeChanged) add(context.getString(R.string.backup_restart_needed))
    }.joinToString(" · ")
    is BackupRestoreResult.UnsupportedVersion -> context.getString(R.string.backup_error_unsupported_version)
    BackupRestoreResult.InvalidJson, BackupRestoreResult.NotABackup, is BackupRestoreResult.Malformed ->
        context.getString(R.string.backup_error_invalid_file)
    BackupRestoreResult.LibraryEmpty -> context.getString(R.string.backup_error_library_empty)
    BackupRestoreResult.NoMatchingSongs -> context.getString(R.string.backup_error_no_matching_songs)
    is BackupRestoreResult.WriteFailed ->
        context.getString(if (r.rolledBack) R.string.backup_error_rolled_back else R.string.backup_error_restore_failed)
}
