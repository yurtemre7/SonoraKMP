package de.yurtemre.sonora.ui.screens.recent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.library.JumpBackIn
import de.yurtemre.sonora.shared.library.JumpBackItem
import de.yurtemre.sonora.shared.library.Recommendations
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.ScreenExplainer
import de.yurtemre.sonora.ui.components.SongRow
import de.yurtemre.sonora.ui.components.displayTitle
import de.yurtemre.sonora.ui.navigation.Album
import de.yurtemre.sonora.ui.navigation.albumKeyOf
import de.yurtemre.sonora.ui.navigation.artistKeyOf
import de.yurtemre.sonora.ui.sheets.SongActionsHost
import de.yurtemre.sonora.ui.sheets.rememberSongActionsState
import de.yurtemre.sonora.ui.theme.SleeveShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.koinInject

/** Rows behind the Home "Recommended for you" and "Jump back in" sections. */
private const val FULL_LIMIT = 50

data class RecommendedUi(val loaded: Boolean = false, val songs: List<Song> = emptyList())

class RecommendedViewModel(library: LibraryRepository, stats: StatsRepository, clock: Clock) : ViewModel() {
    val ui: StateFlow<RecommendedUi> = combine(library.songs, stats.snapshot) { songs, snap ->
        RecommendedUi(true, Recommendations.recommended(songs, snap, clock.nowMs(), FULL_LIMIT))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecommendedUi())
}

data class JumpBackUi(val loaded: Boolean = false, val albums: List<JumpBackItem> = emptyList())

class JumpBackViewModel(library: LibraryRepository, stats: StatsRepository) : ViewModel() {
    val ui: StateFlow<JumpBackUi> = combine(library.songs, stats.snapshot) { songs, snap ->
        JumpBackUi(true, JumpBackIn.recentAlbums(songs, snap, FULL_LIMIT))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JumpBackUi())
}

/** Every recommended song (up to 50), with a note on how the list is built. Tap plays from here; shuffle in the bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendedRoute(onBack: () -> Unit, onNavigate: (NavKey) -> Unit = {}) {
    val playback: PlaybackConnection = koinInject()
    val library: LibraryRepository = koinInject()
    val stats: StatsRepository = koinInject()
    val clock: Clock = koinInject()
    val vm = viewModel { RecommendedViewModel(library, stats, clock) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by playback.player.collectAsStateWithLifecycle()
    val actions = rememberSongActionsState()
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_recommended)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back)) }
                },
                actions = {
                    if (ui.songs.size > 1) IconButton(onClick = { playback.playShuffled(ui.songs) }) {
                        Icon(Icons.Rounded.Shuffle, stringResource(R.string.recommended_shuffle))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            !ui.loaded -> Box(Modifier.fillMaxSize().padding(padding))
            ui.songs.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.AutoAwesome,
                    title = stringResource(R.string.recommended_empty_title),
                    subtitle = stringResource(R.string.home_recommended_empty),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item(key = "explainer") { ScreenExplainer(stringResource(R.string.recommended_explainer)) }
                items(ui.songs, key = { it.id }) { song ->
                    SongRow(
                        title = song.displayTitle(),
                        artist = song.artist,
                        durationMs = song.durationMs,
                        artwork = song.artworkPath,
                        showArtwork = true,
                        isPlaying = player.currentSong?.id == song.id,
                        onClick = { playback.playSong(song, ui.songs) },
                        onLongClick = { actions.show(song) },
                        onMoreClick = { actions.show(song) },
                        swipeActions = de.yurtemre.sonora.ui.components.rememberQueueSwipeActions(song, snackbar),
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
    SongActionsHost(
        state = actions,
        snackbarHostState = snackbar,
        onSelect = {},
        onGoToAlbum = { onNavigate(albumKeyOf(it)) },
        onGoToArtist = { onNavigate(artistKeyOf(it)) },
    )
}

/** Every recently played album (up to 50), newest first. Tap opens the album, the play button plays it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JumpBackRoute(onBack: () -> Unit, onNavigate: (NavKey) -> Unit = {}) {
    val playback: PlaybackConnection = koinInject()
    val library: LibraryRepository = koinInject()
    val stats: StatsRepository = koinInject()
    val vm = viewModel { JumpBackViewModel(library, stats) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_jump_back_in)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back)) }
                },
            )
        },
    ) { padding ->
        when {
            !ui.loaded -> Box(Modifier.fillMaxSize().padding(padding))
            ui.albums.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.Album,
                    title = stringResource(R.string.jumpback_empty_title),
                    subtitle = stringResource(R.string.jumpback_empty_subtitle),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item(key = "explainer") { ScreenExplainer(stringResource(R.string.jumpback_explainer)) }
                items(ui.albums, key = { "${it.albumName}\u0000${it.albumArtist}" }) { album ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onNavigate(Album(album.albumName, album.albumArtist)) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AlbumArt(album.coverSong.artworkPath, album.albumName, Modifier.size(56.dp), shape = SleeveShape, decorative = true, artist = album.albumArtist)
                        Column(Modifier.weight(1f)) {
                            Text(album.albumName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                album.albumArtist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { if (album.songs.isNotEmpty()) playback.playSong(album.songs.first(), album.songs) }) {
                            Icon(Icons.Rounded.PlayArrow, stringResource(R.string.home_jump_play_cd, album.albumName))
                        }
                    }
                }
            }
        }
    }
}
