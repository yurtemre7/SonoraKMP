package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.components.TintedBackdrop
import de.yurtemre.sonora.ui.components.Visualizer
import de.yurtemre.sonora.ui.components.WavySeekBar
import de.yurtemre.sonora.ui.screens.nowplaying.sheets.EffectsSheet
import de.yurtemre.sonora.ui.screens.nowplaying.sheets.SleepTimerSheet
import org.koin.compose.koinInject

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
) {
    val playback = koinInject<de.yurtemre.sonora.playback.api.PlaybackConnection>()
    val favorites = koinInject<de.yurtemre.sonora.shared.repo.api.FavoritesRepository>()
    val settings = koinInject<de.yurtemre.sonora.shared.settings.SettingsRepository>()
    val vm = viewModel { NowPlayingViewModel(playback, favorites, settings) }
    val state by vm.player.collectAsStateWithLifecycle()
    val queue by playback.queue.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle()
    val immersive by vm.immersive.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    val blurred by vm.blurredBackgrounds.collectAsStateWithLifecycle()
    val showVisualizer by vm.showVisualizer.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(NowPlayingView.Player) }
    var showEffects by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
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
            IconButton(onClick = onCollapse, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.np_collapse))
            }
            if (song == null) return@Column
            val actions = remember(vm, onOpenAlbum, onOpenArtist, openMfx, openSleep) { PlayerActions(
                onTogglePlayPause = vm::togglePlayPause,
                onPrevious = vm::previous,
                onNext = vm::next,
                onToggleShuffle = vm::toggleShuffle,
                onCycleRepeat = vm::cycleRepeat,
                onToggleFavorite = vm::toggleFavorite,
                onToggleImmersive = vm::toggleImmersive,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                onMfx = openMfx,
                onSleepTimer = openSleep,
            ) }
            PlayerView(
                song = song,
                state = state,
                isFavorite = isFavorite,
                immersive = immersive,
                view = view,
                onViewChange = { view = it },
                actions = actions,
                queue = queue,
                onSkipTo = vm::skipTo,
                style = style,
                visualizer = if (showVisualizer) {
                    { Visualizer(state.isPlaying, Modifier.padding(bottom = 8.dp)) }
                } else null,
                elapsed = { LiveElapsed(vm, state.durationMs) },
                seekBar = { if (seekBar != null) seekBar() else LiveSeekBar(vm, state.durationMs, state.isPlaying) },
                upNext = {
                    if (upNext != null) upNext()
                    else UpNextView(snackbarHostState ?: remember { SnackbarHostState() }, connection = playback)
                },
                lyrics = {
                    val position by vm.positionMs.collectAsStateWithLifecycle(0L)
                    LyricsView(song, position, vm::seekTo, Modifier.fillMaxSize())
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

/** Large tabular elapsed / total time for the minimalist style. */
@Composable
private fun LiveElapsed(vm: NowPlayingViewModel, durationMs: Long) {
    val position by vm.positionMs.collectAsStateWithLifecycle(0L)
    MinimalTime(position, maxOf(durationMs, position))
}
