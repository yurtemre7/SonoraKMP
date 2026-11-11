package de.yurtemre.sonora.ui.screens.settings.playback

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.model.ReplayGainMode
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.screens.settings.ChoiceRow
import de.yurtemre.sonora.ui.screens.settings.ConnectedChoice
import de.yurtemre.sonora.ui.screens.settings.SettingsRow
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold
import de.yurtemre.sonora.ui.screens.settings.SwitchRow
import de.yurtemre.sonora.ui.screens.settings.settingsHeader
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * SET-04: every playback setting of audit/settings §5. The playback service collects [SettingsRepository.settings],
 * so each write reaches the running player immediately.
 */
@Composable
fun SettingsPlaybackRoute(onBack: () -> Unit) {
    val repo: SettingsRepository = koinInject()
    val s by repo.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    fun save(block: suspend SettingsRepository.() -> Unit) { scope.launch { repo.block() } }

    SettingsScaffold(stringResource(R.string.playback), onBack) {
        settingsHeader("playing") { stringResource(R.string.settings_section_while_playing) }
        item(key = "replay_gain") {
            SettingsRow(
                stringResource(R.string.settings_replay_gain), icon = Icons.Rounded.GraphicEq,
                subtitle = stringResource(R.string.settings_replay_gain_subtitle),
            )
        }
        item(key = "replay_gain_choice") {
            ConnectedChoice(
                options = ReplayGainMode.entries,
                selected = s.replayGainMode,
                onSelect = { save { setReplayGainMode(it) } },
                label = {
                    stringResource(
                        when (it) {
                            ReplayGainMode.Off -> R.string.settings_replay_gain_off
                            ReplayGainMode.Track -> R.string.settings_replay_gain_track
                            ReplayGainMode.Album -> R.string.settings_replay_gain_album
                        },
                    )
                },
            )
        }
        item(key = "keep_playing") {
            SwitchRow(
                stringResource(R.string.settings_keep_playing), s.keepPlayingOnClose, { save { setKeepPlayingOnClose(it) } },
                icon = Icons.Outlined.PlayCircle, subtitle = stringResource(R.string.settings_keep_playing_subtitle),
            )
        }
        item(key = "restore_last") {
            SwitchRow(
                stringResource(R.string.settings_restore_last), s.restoreLastPlayedSong,
                { save { setRestoreLastPlayedSong(it) } },
                icon = Icons.Rounded.History, subtitle = stringResource(R.string.settings_restore_last_subtitle),
            )
        }
        item(key = "pause_on_duck") {
            SwitchRow(
                stringResource(R.string.settings_pause_on_duck), s.pauseOnDuck, { save { setPauseOnDuck(it) } },
                icon = Icons.Rounded.NotificationsPaused, subtitle = stringResource(R.string.settings_pause_on_duck_subtitle),
            )
        }

        settingsHeader("headphones") { stringResource(R.string.settings_section_headphones) }
        item(key = "pause_on_disconnect") {
            SwitchRow(
                stringResource(R.string.settings_pause_on_disconnect), s.pauseOnDisconnect,
                { save { setPauseOnDisconnect(it) } }, icon = Icons.Rounded.HeadsetOff,
                subtitle = stringResource(R.string.settings_pause_on_disconnect_subtitle),
            )
        }
        item(key = "resume_on_connect") {
            SwitchRow(
                stringResource(R.string.settings_resume_on_connect), s.resumeOnConnect,
                { save { setResumeOnConnect(it) } }, icon = Icons.Rounded.Headset,
                subtitle = stringResource(R.string.settings_resume_on_connect_subtitle),
            )
        }
        item(key = "bt_patch") {
            SwitchRow(
                stringResource(R.string.settings_bt_patch), s.bluetoothMfxPatch, { save { setBluetoothMfxPatch(it) } },
                icon = Icons.Rounded.BluetoothAudio, subtitle = stringResource(R.string.settings_bt_patch_subtitle),
            )
        }

        settingsHeader("sleep") { stringResource(R.string.settings_section_sleep_timer) }
        item(key = "sleep_default") {
            ChoiceRow(
                title = stringResource(R.string.settings_sleep_default),
                options = AppSettings.SLEEP_TIMER_MINUTES_OPTIONS,
                // Invalid stored values are shown as 60 (audit/settings §5).
                selected = s.sleepTimerDefaultMinutes.takeIf { it in AppSettings.SLEEP_TIMER_MINUTES_OPTIONS } ?: 60,
                label = { stringResource(R.string.settings_minutes, it) },
                onSelect = { save { setSleepTimerDefaultMinutes(it) } },
                icon = Icons.Rounded.Timer,
                subtitle = stringResource(R.string.settings_sleep_default_subtitle),
            )
        }
        item(key = "sleep_fade") {
            ChoiceRow(
                title = stringResource(R.string.settings_sleep_fade),
                options = AppSettings.SLEEP_TIMER_FADE_OPTIONS,
                selected = s.sleepTimerFadeOutSeconds.takeIf { it in AppSettings.SLEEP_TIMER_FADE_OPTIONS } ?: 10,
                label = { if (it == 0) stringResource(R.string.settings_off) else stringResource(R.string.settings_seconds, it) },
                onSelect = { save { setSleepTimerFadeOutSeconds(it) } },
                icon = Icons.Rounded.VolumeDown,
                subtitle = stringResource(R.string.settings_sleep_fade_subtitle),
            )
        }
        item(key = "finish_song") {
            SwitchRow(
                stringResource(R.string.settings_finish_song), s.sleepTimerFinishSong, { save { setSleepTimerFinishSong(it) } },
                icon = Icons.Rounded.MusicNote, subtitle = stringResource(R.string.settings_finish_song_subtitle),
            )
        }

        settingsHeader("startup") { stringResource(R.string.settings_section_startup) }
        item(key = "start_page") {
            ChoiceRow(
                title = stringResource(R.string.settings_start_page),
                options = listOf(0, 1, 2, 3),
                selected = s.defaultStartPage,
                label = { stringResource(StartPageLabels[it]) },
                onSelect = { save { setDefaultStartPage(it) } },
                icon = Icons.Rounded.Home,
                subtitle = stringResource(R.string.settings_start_page_subtitle),
            )
        }
    }
}

private val StartPageLabels = listOf(
    R.string.settings_tab_songs, R.string.settings_tab_albums, R.string.settings_tab_artists, R.string.settings_tab_playlists,
)
