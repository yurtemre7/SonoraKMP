package de.yurtemre.sonora.ui.screens.recent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.yurtemre.sonora.R
import de.yurtemre.sonora.platform.ShareHelper
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.stats.StatsAggregates
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.MultiSelectActionBar
import de.yurtemre.sonora.ui.components.SectionHeader
import de.yurtemre.sonora.ui.components.SongRow
import de.yurtemre.sonora.ui.components.rememberSelectionState
import de.yurtemre.sonora.ui.format.relativeDayText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class RecentDay(val date: LocalDate, val songs: List<Song>)

data class RecentUi(
    val loaded: Boolean = false,
    /** Most recent first; the play order. */
    val songs: List<Song> = emptyList(),
    val days: List<RecentDay> = emptyList(),
    val plays: Map<Long, Int> = emptyMap(),
)

class RecentlyPlayedViewModel(library: LibraryRepository, stats: StatsRepository) : ViewModel() {
    val ui: StateFlow<RecentUi> = combine(library.songs, stats.snapshot) { songs, snap ->
        val recent = StatsAggregates.recentSongs(snap, songs)
        val zone = ZoneId.systemDefault()
        val days = recent.groupBy { Instant.ofEpochMilli(snap.songs[it.id]!!.lastPlayedAtMs!!).atZone(zone).toLocalDate() }
            .map { (d, s) -> RecentDay(d, s) }
        RecentUi(true, recent, days, recent.associate { it.id to StatsAggregates.plays(snap, it.id) })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentUi())
}

/** UI-27 Recently played: grouped by day (Today / Yesterday / weekday / date), play counts, multi-select. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecentlyPlayedRoute(onBack: () -> Unit) {
    val playback: PlaybackConnection = koinInject()
    val library: LibraryRepository = koinInject()
    val stats: StatsRepository = koinInject()
    val favorites: FavoritesRepository = koinInject()
    val vm = viewModel { RecentlyPlayedViewModel(library, stats) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by playback.player.collectAsStateWithLifecycle()
    val selection = rememberSelectionState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val today = remember { LocalDate.now() }

    BackHandler(enabled = selection.active) { selection.clear() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recent_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back)) }
                },
                actions = {
                    if (ui.songs.size > 1) IconButton(onClick = { playback.playShuffled(ui.songs) }) {
                        Icon(Icons.Rounded.Shuffle, stringResource(R.string.recent_shuffle))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selection.active) {
                val byId = remember(ui.songs) { ui.songs.associateBy { it.id } }
                fun pick(ids: Set<Long>) = ui.songs.filter { it.id in ids }
                MultiSelectActionBar(
                    selection = selection,
                    allIds = { ui.songs.map { it.id } },
                    snackbarHostState = snackbar,
                    onPlayNext = { playback.playNext(pick(it)) },
                    onAddToQueue = { playback.addToQueue(pick(it)) },
                    onAddToPlaylist = {},
                    allFavorite = selection.selected.isNotEmpty() && selection.selected.all { byId[it]?.isFavorite == true },
                    onFavoriteToggle = { ids, fav ->
                        scope.launch { favorites.setSongsFavorite(pick(ids).associate { it.id to it.filePath }, fav) }
                    },
                    shareItems = { ids -> pick(ids).map { ShareHelper.Item(it.filePath, it.id, it.format?.let { f -> "audio/$f" }) } },
                )
            }
        },
    ) { padding ->
        when {
            !ui.loaded -> Box(Modifier.fillMaxSize().padding(padding))
            ui.songs.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.recent_empty_title),
                    subtitle = stringResource(R.string.recent_empty_subtitle),
                    action = { FilledTonalButton(onClick = onBack) { Text(stringResource(R.string.recent_empty_action)) } },
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                ui.days.forEach { day ->
                    stickyHeader(key = "d_${day.date}") {
                        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                            SectionHeader(relativeDayText(day.date, today), count = day.songs.size)
                        }
                    }
                    items(day.songs, key = { it.id }) { song ->
                        val plays = ui.plays[song.id] ?: 0
                        SongRow(
                            title = song.title,
                            artist = stringResource(
                                R.string.recent_row_artist, song.artist,
                                pluralStringResource(R.plurals.recent_plays, plays, plays),
                            ),
                            durationMs = song.durationMs,
                            artwork = song.artworkPath,
                            showArtwork = true,
                            isPlaying = player.currentSong?.id == song.id,
                            selected = selection.isSelected(song.id),
                            onClick = { if (!selection.onTap(song.id)) playback.playSong(song, ui.songs) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selection.onLongPress(song.id)
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}
