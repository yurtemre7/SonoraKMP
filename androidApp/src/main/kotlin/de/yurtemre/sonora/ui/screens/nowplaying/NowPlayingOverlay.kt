package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.SleepTimerUiState
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.components.MfxBadge
import de.yurtemre.sonora.ui.components.SleepTimerBadge
import de.yurtemre.sonora.ui.components.TintedBackdrop
import de.yurtemre.sonora.ui.components.Visualizer
import de.yurtemre.sonora.ui.components.WavySeekBar
import de.yurtemre.sonora.ui.screens.nowplaying.sheets.EffectsSheet
import de.yurtemre.sonora.ui.screens.nowplaying.sheets.SleepTimerSheet
import de.yurtemre.sonora.ui.sheets.SongInfoSheet
import org.koin.compose.koinInject
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.jam.JamController
import de.yurtemre.sonora.ui.navigation.Jam
import de.yurtemre.sonora.ui.screens.jam.JamBanner
import de.yurtemre.sonora.ui.screens.jam.JamMenu
import de.yurtemre.sonora.ui.screens.jam.JamGuestActions
import de.yurtemre.sonora.ui.screens.jam.JamGuestProgress
import de.yurtemre.sonora.ui.screens.jam.JamQueueView
import de.yurtemre.sonora.ui.screens.jam.JamStartSheet
import de.yurtemre.sonora.ui.screens.jam.LocalJamPlayerUi
import de.yurtemre.sonora.ui.screens.jam.joinedOrNull
import de.yurtemre.sonora.ui.screens.jam.jamPlayerUi

/**
 * Full-screen Now Playing (UI-16/17/22). Back from a non-player view returns to the player first;
 * the slot's predictive back then collapses the overlay.
 */
@Composable
fun NowPlayingOverlay(
    onCollapse: () -> Unit,
    onOpenAlbum: (Song) -> Unit,
    onOpenArtist: (Song) -> Unit,
    modifier: Modifier = Modifier,
    onMfx: (() -> Unit)? = null,
    onSleepTimer: (() -> Unit)? = null,
    seekBar: (@Composable () -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    upNext: (@Composable () -> Unit)? = null,
    dragModifier: Modifier = Modifier,
    onJam: (NavKey) -> Unit = {},
) {
    val playback = koinInject<de.yurtemre.sonora.playback.api.PlaybackConnection>()
    val favorites = koinInject<de.yurtemre.sonora.shared.repo.api.FavoritesRepository>()
    val settings = koinInject<de.yurtemre.sonora.shared.settings.SettingsRepository>()
    val vm = viewModel { NowPlayingViewModel(playback, favorites, settings) }
    val state by vm.player.collectAsStateWithLifecycle()
    val queue by playback.queue.collectAsStateWithLifecycle()
    val sleepTimer by playback.sleepTimer.collectAsStateWithLifecycle()
    val mfx by playback.mfx.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle()
    val immersive by vm.immersive.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    val titleStyle by vm.titleStyle.collectAsStateWithLifecycle()
    val blurred by vm.blurredBackgrounds.collectAsStateWithLifecycle()
    val showVisualizer by vm.showVisualizer.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(NowPlayingView.Player) }
    var showEffects by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showJamStart by rememberSaveable { mutableStateOf(false) }
    // Jam: a guest's player mirrors the host, so controls that would diverge are hidden or become requests.
    val jamController = koinInject<JamController>()
    val jamState by jamController.state.collectAsStateWithLifecycle()
    val jamUi = jamPlayerUi(jamState)
    val joined by rememberUpdatedState(jamState.joinedOrNull())
    val guestActions = remember(jamController) { JamGuestActions(jamController) }
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    if (showJamStart) JamStartSheet(
        onDismiss = { showJamStart = false },
        onStart = { settings -> showJamStart = false; onJam(Jam.hosting(settings)) },
    )
    val openMfx: () -> Unit = onMfx ?: { showEffects = true }
    val openSleep: () -> Unit = onSleepTimer ?: { showSleep = true }
    if (showEffects) EffectsSheet(onDismiss = { showEffects = false }, connection = playback)
    if (showSleep) SleepTimerSheet(onDismiss = { showSleep = false }, connection = playback)

    // Composed after the slot's handler, so it wins while a secondary view is open.
    BackHandler(enabled = view != NowPlayingView.Player) { view = NowPlayingView.Player }

    val song = state.currentSong
    TintedBackdrop(
        song?.seedColor?.let { Color(it) },
        modifier.fillMaxSize(),
        blurModel = if (blurred) song?.artworkPath else null,
    ) {
        // TintedBackdrop is not a Surface, so icons would default to black in dark theme.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
        ) {
        Column(Modifier.fillMaxSize().then(dragModifier).safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.np_collapse))
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MfxBadge(mfx, onClick = openMfx)
                    if (jamUi.showSleepTimer) SleepTimerBadge(sleepTimer, onClick = openSleep)
                }
                if (song != null) {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Outlined.Info, stringResource(R.string.song_info))
                    }
                }
                JamMenu(
                    jamUi,
                    onStart = { showJamStart = true },
                    onJoin = { onJam(Jam(join = true)) },
                    onOpen = { onJam(Jam()) },
                    onLeave = jamController::leave,
                )
            }
            JamBanner(
                jamUi,
                onOpen = { onJam(Jam()) },
                onLeave = jamController::leave,
                onDismissEnded = jamController::dismiss,
            )
            if (song == null) return@Column
            if (showInfo) SongInfoSheet(song, onDismiss = { showInfo = false })
            val actions = remember(vm, onOpenAlbum, onOpenArtist, openMfx, openSleep, guestActions) { PlayerActions(
                onTogglePlayPause = { joined?.let(guestActions::playPause) ?: vm.togglePlayPause() },
                onPrevious = { joined?.let(guestActions::previous) ?: vm.previous() },
                onNext = { joined?.let(guestActions::next) ?: vm.next() },
                onToggleShuffle = vm::toggleShuffle,
                onCycleRepeat = vm::cycleRepeat,
                onToggleFavorite = vm::toggleFavorite,
                onToggleImmersive = vm::toggleImmersive,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                onMfx = openMfx,
                onSleepTimer = openSleep,
            ) }
            CompositionLocalProvider(LocalJamPlayerUi provides jamUi) {
            val guest = joined
            PlayerView(
                song = song,
                state = state,
                isFavorite = isFavorite,
                immersive = immersive,
                view = view,
                onViewChange = { view = it },
                actions = actions,
                queue = if (guest != null) null else queue,
                onSkipTo = { if (guest == null) vm.skipTo(it) },
                style = style,
                titleStyle = titleStyle,
                visualizer = if (showVisualizer) {
                    { Visualizer(state.isPlaying, Modifier.padding(bottom = 8.dp)) }
                } else null,
                elapsed = { LiveElapsed(vm, state.durationMs) },
                sleepTimerActive = sleepTimer !is SleepTimerUiState.Inactive,
                mfxActive = mfx.isActive,
                seekBar = {
                    when {
                        !jamUi.showSeek -> LiveGuestProgress(vm, state.durationMs)
                        seekBar != null -> seekBar()
                        else -> LiveSeekBar(vm, state.durationMs, state.isPlaying)
                    }
                },
                upNext = {
                    if (guest != null) JamQueueView(guest, onAdd = { s ->
                        val sent = joined?.let { guestActions.add(it, s) } == true
                        if (sent && snackbarHostState != null) scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(res.getString(R.string.jam_add_sent, s.title))
                        }
                    })
                    else if (upNext != null) upNext()
                    else UpNextView(snackbarHostState ?: remember { SnackbarHostState() }, connection = playback)
                },
                lyrics = {
                    val position by vm.positionMs.collectAsStateWithLifecycle(0L)
                    LyricsView(song, position, { if (guest == null) vm.seekTo(it) }, Modifier.fillMaxSize())
                },
                related = {
                    RelatedView(
                        song = song,
                        onPlay = { s, list -> playback.playSong(s, list) },
                        onOpenAlbum = { onOpenAlbum(song) },
                        onOpenArtist = { onOpenArtist(song) },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
            }
        }
        }
    }
}

/** Wavy seek bar fed by the live 200 ms position ticker (PLAY-25). */
@Composable
private fun LiveSeekBar(vm: NowPlayingViewModel, durationMs: Long, isPlaying: Boolean) {
    val position by vm.positionMs.collectAsStateWithLifecycle(0L)
    WavySeekBar(
        positionMs = position,
        durationMs = maxOf(durationMs, position),
        isPlaying = isPlaying,
        onSeek = vm::seekTo,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A Jam guest's position without seeking. */
@Composable
private fun LiveGuestProgress(vm: NowPlayingViewModel, durationMs: Long) {
    val position by vm.positionMs.collectAsStateWithLifecycle(0L)
    JamGuestProgress(position, maxOf(durationMs, position))
}

/** Large tabular elapsed / total time for the minimalist style. */
@Composable
private fun LiveElapsed(vm: NowPlayingViewModel, durationMs: Long) {
    val position by vm.positionMs.collectAsStateWithLifecycle(0L)
    MinimalTime(position, maxOf(durationMs, position))
}
