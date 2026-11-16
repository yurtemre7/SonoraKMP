package de.yurtemre.sonora.ui.screens.smartlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.library.SmartListKind
import de.yurtemre.sonora.shared.library.SmartLists
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.stats.StatsAggregates
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.SongRow
import de.yurtemre.sonora.ui.components.displayTitle
import de.yurtemre.sonora.ui.components.rememberQueueSwipeActions
import de.yurtemre.sonora.ui.navigation.albumKeyOf
import de.yurtemre.sonora.ui.navigation.artistKeyOf
import de.yurtemre.sonora.ui.screens.home.tabs.emptyRes
import de.yurtemre.sonora.ui.screens.home.tabs.icon
import de.yurtemre.sonora.ui.screens.home.tabs.titleRes
import de.yurtemre.sonora.ui.sheets.SongActionsHost
import de.yurtemre.sonora.ui.sheets.rememberSongActionsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.koinInject

data class SmartListUi(val loaded: Boolean = false, val songs: List<Song> = emptyList())

class SmartListViewModel(kind: SmartListKind, library: LibraryRepository, stats: StatsRepository) : ViewModel() {
    val ui: StateFlow<SmartListUi> = combine(library.songs, stats.snapshot) { songs, snap ->
        SmartListUi(true, SmartLists.build(kind, songs) { StatsAggregates.plays(snap, it) })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartListUi())
}

/** A built-in smart list: recently added, never played or most played songs, with shuffle and the song actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartListRoute(kindKey: String, onBack: () -> Unit, onNavigate: (NavKey) -> Unit = {}) {
    val kind = remember(kindKey) { SmartListKind.fromKey(kindKey) }
    val actions = rememberSongActionsState()
    val playback: PlaybackConnection = koinInject()
    val library: LibraryRepository = koinInject()
    val stats: StatsRepository = koinInject()
    val vm = viewModel(key = "smart_${kind.key}") { SmartListViewModel(kind, library, stats) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by playback.player.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(kind.titleRes())) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back)) }
                },
                actions = {
                    if (ui.songs.size > 1) IconButton(onClick = { playback.playShuffled(ui.songs) }) {
                        Icon(Icons.Rounded.Shuffle, stringResource(R.string.smart_list_shuffle))
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
                    icon = kind.icon(),
                    title = stringResource(R.string.smart_list_empty_title),
                    subtitle = stringResource(kind.emptyRes()),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(ui.songs, key = { it.id }) { song ->
                    SongRow(
                        title = song.displayTitle(),
                        artist = song.artist,
                        durationMs = song.durationMs,
                        artwork = song.artworkPath,
                        showArtwork = true,
                        isPlaying = player.currentSong?.id == song.id,
                        onClick = { playback.playSong(song, ui.songs) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.show(song)
                        },
                        onMoreClick = { actions.show(song) },
                        swipeActions = rememberQueueSwipeActions(song, snackbar),
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
    SongActionsHost(
        state = actions,
        snackbarHostState = snackbar,
        onGoToAlbum = { onNavigate(albumKeyOf(it)) },
        onGoToArtist = { onNavigate(artistKeyOf(it)) },
    )
}
