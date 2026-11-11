package de.yurtemre.sonora.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.BuildConfig
import de.yurtemre.sonora.R
import de.yurtemre.sonora.locale.AppLocales
import de.yurtemre.sonora.permissions.FolderPicker
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.navigation.DebugCaches
import de.yurtemre.sonora.ui.navigation.SettingsAppearance
import de.yurtemre.sonora.ui.navigation.SettingsBackup
import de.yurtemre.sonora.ui.navigation.SettingsFormatting
import de.yurtemre.sonora.ui.navigation.SettingsInfo
import de.yurtemre.sonora.ui.navigation.SettingsPlayback
import de.yurtemre.sonora.ui.navigation.SettingsPrivacy
import de.yurtemre.sonora.ui.navigation.Stats
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings root (SET-01, L10N-03): library card, stats, language, sub-screens, updates, debug.
 * [onNavigate] pushes a destination; [onOpenUpdates] opens the update/channel screen (P5-22); without one the Info screen hosts the update tiles.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onNavigate: (NavKey) -> Unit = {},
    onOpenUpdates: (() -> Unit)? = null,
) {
    val settingsRepository: SettingsRepository = koinInject()
    val libraryRepository: LibraryRepository = koinInject()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
    val songs by libraryRepository.songs.collectAsStateWithLifecycle(emptyList())
    val repoSyncing by libraryRepository.isSyncing.collectAsStateWithLifecycle(false)
    val context = LocalContext.current
    val contextRes = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var localSyncing by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    val syncing = repoSyncing || localSyncing

    fun runSync() {
        if (localSyncing) return
        localSyncing = true
        scope.launch {
            val outcome = libraryRepository.sync()
            localSyncing = false
            val msg = when (outcome) {
                is SyncOutcome.Success -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    contextRes.getQuantityString(
                        R.plurals.settings_library_sync_done, outcome.songCount, outcome.songCount,
                        (outcome.durationMs / 100) / 10.0,
                    )
                }
                SyncOutcome.FolderMissing -> contextRes.getString(R.string.settings_library_sync_folder_missing)
                is SyncOutcome.Failed -> contextRes.getString(R.string.settings_library_sync_failed)
            }
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = FolderPicker.persist(context, uri)
        if (picked == null) {
            scope.launch { snackbar.showSnackbar(contextRes.getString(R.string.settings_library_folder_invalid)) }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            settingsRepository.setScanFolder(picked.path, picked.uri)
            runSync()
        }
    }

    SettingsScaffold(stringResource(R.string.nav_title_settings), onBack, snackbar) {
        settingsHeader("library") { stringResource(R.string.settings_section_library) }
        item(key = "library_card") {
            LibrarySyncCard(
                folderPath = settings.scanFolderPath,
                lastSyncMs = settings.lastSyncTimestampMs,
                songCount = songs.size,
                syncing = syncing,
                onChangeFolder = { treeLauncher.launch(null) },
                onSyncNow = ::runSync,
            )
        }
        item(key = "stats") {
            SettingsRow(
                title = stringResource(R.string.settings_stats_title),
                subtitle = stringResource(R.string.view_statistics_subtitle),
                icon = Icons.Outlined.Analytics,
                chevron = true,
                onClick = { onNavigate(Stats) },
            )
        }

        settingsHeader("general") { stringResource(R.string.settings_section_general) }
        item(key = "language") {
            SettingsRow(
                title = stringResource(R.string.settings_language_title),
                subtitle = languageLabel(settings.appLocale),
                icon = Icons.Rounded.Language,
                onClick = { showLanguage = true },
            )
        }
        item(key = "appearance") {
            SettingsRow(
                stringResource(R.string.settings_appearance_title), icon = Icons.Outlined.Palette,
                subtitle = stringResource(R.string.settings_appearance_subtitle), chevron = true,
                onClick = { onNavigate(SettingsAppearance) },
            )
        }
        item(key = "formatting") {
            SettingsRow(
                stringResource(R.string.settings_formatting_title), icon = Icons.Rounded.Title,
                subtitle = stringResource(R.string.formatting_subtitle), chevron = true,
                onClick = { onNavigate(SettingsFormatting) },
            )
        }
        item(key = "playback") {
            SettingsRow(
                stringResource(R.string.playback), icon = Icons.Outlined.PlayCircle,
                subtitle = stringResource(R.string.settings_playback_subtitle), chevron = true,
                onClick = { onNavigate(SettingsPlayback) },
            )
        }

        settingsHeader("data") { stringResource(R.string.settings_section_data) }
        item(key = "privacy") {
            SettingsRow(
                stringResource(R.string.settings_privacy_title), icon = Icons.Outlined.PrivacyTip,
                subtitle = stringResource(R.string.privacy_subtitle), chevron = true,
                onClick = { onNavigate(SettingsPrivacy) },
            )
        }
        item(key = "backup") {
            SettingsRow(
                stringResource(R.string.settings_backup_title), icon = Icons.Rounded.SettingsBackupRestore,
                subtitle = stringResource(R.string.settings_backup_subtitle), chevron = true,
                onClick = { onNavigate(SettingsBackup) },
            )
        }

        settingsHeader("about") { stringResource(R.string.settings_section_about) }
        item(key = "updates") {
            SettingsRow(
                stringResource(R.string.settings_updates_title), icon = Icons.Rounded.SystemUpdate,
                subtitle = stringResource(R.string.settings_updates_subtitle), chevron = true,
                onClick = { onOpenUpdates?.invoke() ?: onNavigate(SettingsInfo) },
            )
        }
        item(key = "info") {
            SettingsRow(
                stringResource(R.string.settings_info_title), icon = Icons.Outlined.Info,
                subtitle = stringResource(R.string.settings_info_subtitle), chevron = true,
                onClick = { onNavigate(SettingsInfo) },
            )
        }
        if (BuildConfig.DEBUG) {
            settingsHeader("debug") { stringResource(R.string.settings_section_developer) }
            item(key = "debug") {
                SettingsRow(
                    stringResource(R.string.settings_debug_title), icon = Icons.Rounded.BugReport,
                    subtitle = stringResource(R.string.settings_debug_subtitle), chevron = true,
                    onClick = { onNavigate(DebugCaches) },
                )
            }
        }
    }

    if (showLanguage) {
        LanguageDialog(
            current = settings.appLocale,
            onSelect = { locale ->
                showLanguage = false
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch {
                    settingsRepository.setAppLocale(locale)
                    AppLocales.apply(locale)
                }
            },
            onDismiss = { showLanguage = false },
        )
    }
}
