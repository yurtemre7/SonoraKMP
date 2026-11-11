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
import de.yurtemre.sonora.shared.model.NowPlayingStyle
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.components.TintedBackdrop
import de.yurtemre.sonora.ui.components.WavySeekBar
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
    onMfx: () -> Unit = {},
    onSleepTimer: () -> Unit = {},
    seekBar: (@Composable () -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    upNext: (@Composable () -> Unit)? = null,
) {
    val playback = koinInject<de.yurtemre.sonora.playback.api.PlaybackConnection>()
    val favorites = koinInject<de.yurtemre.sonora.shared.repo.api.FavoritesRepository>()
    val settings = koinInject<de.yurtemre.sonora.shared.settings.SettingsRepository>()
    val vm = viewModel { NowPlayingViewModel(playback, favorites, settings) }
    val state by vm.player.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle()
    val immersive by vm.immersive.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(NowPlayingView.Player) }

    // Composed after the slot's handler, so it wins while a secondary view is open.
    BackHandler(enabled = view != NowPlayingView.Player) { view = NowPlayingView.Player }

    val song = state.currentSong
    TintedBackdrop(song?.seedColor?.let { Color(it) }, modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            IconButton(onClick = onCollapse, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.np_collapse))
            }
            if (song == null) return@Column
            val actions = PlayerActions(
                onTogglePlayPause = vm::togglePlayPause,
                onPrevious = vm::previous,
                onNext = vm::next,
                onToggleShuffle = vm::toggleShuffle,
                onCycleRepeat = vm::cycleRepeat,
                onToggleFavorite = vm::toggleFavorite,
                onToggleImmersive = vm::toggleImmersive,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                onMfx = onMfx,
                onSleepTimer = onSleepTimer,
            )
            // TODO(P5-07): Vinyl and Minimalist layouts; every style renders Modern for now.
            @Suppress("UNUSED_VARIABLE") val layoutStyle: NowPlayingStyle = style
            PlayerView(
                song = song,
                state = state,
                isFavorite = isFavorite,
                immersive = immersive,
                view = view,
                onViewChange = { view = it },
                actions = actions,
                seekBar = { if (seekBar != null) seekBar() else LiveSeekBar(vm, state.durationMs, state.isPlaying) },
                upNext = {
                    if (upNext != null) upNext()
                    else UpNextView(snackbarHostState ?: remember { SnackbarHostState() }, connection = playback)
                },
            )
        }
    }
}

/** Wavy seek bar fed by the live 200 ms position ticker (PLAY-25). */
@Composable
private fun LiveSeekBar(vm: NowPlayingViewModel, durationMs: Long, isPlaying: Boolean) {
    val position by vm.positionMs.collectAsStateWithLifecycle(0L)
    WavySeekBar(
        positionMs = position,
        durationMs = durationMs,
        isPlaying = isPlaying,
        onSeek = vm::seekTo,
        modifier = Modifier.fillMaxWidth(),
    )
}
