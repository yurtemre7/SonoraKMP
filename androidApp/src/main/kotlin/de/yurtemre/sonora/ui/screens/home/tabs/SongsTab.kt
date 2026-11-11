package de.yurtemre.sonora.ui.screens.home.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.platform.ShareHelper
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.library.ScrollSection
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.model.SongSort
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.FastScroller
import de.yurtemre.sonora.ui.components.binLabel
import de.yurtemre.sonora.ui.components.binStarts
import de.yurtemre.sonora.ui.components.binsOf
import de.yurtemre.sonora.ui.components.rememberQueueSwipeActions
import de.yurtemre.sonora.ui.components.songBin
import androidx.compose.foundation.lazy.items
import de.yurtemre.sonora.ui.components.MultiSelectActionBar
import de.yurtemre.sonora.ui.components.SectionHeader
import de.yurtemre.sonora.ui.components.SongRow
import de.yurtemre.sonora.ui.components.SongRowAction
import de.yurtemre.sonora.ui.components.rememberSelectionState
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.screens.home.HomeTabReselect
import de.yurtemre.sonora.ui.screens.home.HomeTabState
import de.yurtemre.sonora.ui.screens.home.HomeViewModel
import kotlinx.coroutines.launch
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.ui.navigation.albumKeyOf
import de.yurtemre.sonora.ui.navigation.artistKeyOf
import de.yurtemre.sonora.ui.sheets.PlaylistSelectorSheetHost
import de.yurtemre.sonora.ui.sheets.SongActionsHost
import de.yurtemre.sonora.ui.sheets.rememberSongActionsState
import org.koin.compose.koinInject

/** Songs tab (UI-08) with the sync banner (UI-12). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsTab(
    state: HomeTabState,
    vm: HomeViewModel,
    isSyncing: Boolean,
    snackbar: SnackbarHostState,
    onNavigate: (NavKey) -> Unit = {},
) {
    val actions = rememberSongActionsState()
    var addToPlaylistIds by remember { mutableStateOf<List<Long>?>(null) }
    val playback = koinInject<PlaybackConnection>()
    val favorites = koinInject<FavoritesRepository>()
    val player by playback.player.collectAsStateWithLifecycle()
    val showBanner by vm.syncBanner.collectAsStateWithLifecycle()
    val selection = rememberSelectionState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val res = LocalContext.current.resources
    val songs = state.songs
    val sort = SongSort.fromStorage(state.sort.key)
    val byId = remember(songs) { songs.associateBy { it.id } }
    fun pick(ids: Set<Long>) = songs.filter { it.id in ids }
    fun snack(text: String) = scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }

    val sync: () -> Unit = {
        scope.launch {
            val text = when (val r = vm.sync()) {
                is SyncOutcome.Success -> res.getQuantityString(R.plurals.songs_sync_done, r.songCount, r.songCount, r.durationMs.toInt())
                SyncOutcome.FolderMissing -> res.getString(R.string.songs_sync_folder_missing)
                is SyncOutcome.Failed -> res.getString(R.string.songs_sync_failed)
            }
            snack(text)
        }
    }
    val postponedMsg = stringResource(R.string.songs_banner_postponed)

    LaunchedEffect(listState) {
        HomeTabReselect.events.collect { if (it == LibraryTab.Songs) listState.animateScrollToItem(0) }
    }

    Column(Modifier.fillMaxSize()) {
        if (isSyncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (showBanner) SyncBanner(
            enabled = !isSyncing,
            onSyncNow = sync,
            onLater = { vm.postponeSyncReminder(); snack(postponedMsg) },
        )
        Box(Modifier.weight(1f)) {
            when {
                !state.loaded -> Unit
                state.totalCount == 0 -> EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = stringResource(R.string.songs_empty_title),
                    subtitle = stringResource(R.string.songs_empty_subtitle),
                    modifier = Modifier.fillMaxSize(),
                    action = { Button(onClick = sync, enabled = !isSyncing) { Text(stringResource(R.string.songs_rescan)) } },
                )
                songs.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = stringResource(R.string.songs_no_results_title),
                    subtitle = stringResource(R.string.songs_no_results_subtitle, state.query.trim()),
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Box(Modifier.fillMaxSize()) {
                    val bins = remember(songs, sort, state.playCounts) {
                        binsOf(songs) { songBin(it, sort) { s -> state.playCounts[s.id] ?: 0 } }
                    }
                    val labels = bins.map { binLabel(it.key) }
                    val starts = remember(bins) { binStarts(bins) }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        bins.forEach { bin ->
                            items(bin.items, key = { it.id }, contentType = { "song" }) { song ->
                                SongItem(
                                    song = song,
                                    subtitle = if (sort == SongSort.Plays) {
                                        val n = state.playCounts[song.id] ?: 0
                                        "${song.artist} · ${res.getQuantityString(R.plurals.songs_listens_count, n, n)}"
                                    } else song.artist,
                                    isPlaying = player.currentSong?.id == song.id,
                                    selected = selection.isSelected(song.id),
                                    selectionActive = selection.active,
                                    snackbar = snackbar,
                                    onClick = { if (!selection.onTap(song.id)) playback.playSong(song, songs) },
                                    onLongClick = { if (selection.active) selection.onLongPress(song.id) else actions.show(song) },
                                    onFavorite = { scope.launch { favorites.toggleSong(song.id, song.filePath) } },
                                )
                            }
                        }
                    }
                    FastScroller(listState, labels, starts, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
        if (selection.active) MultiSelectActionBar(
            selection = selection,
            allIds = { songs.map { it.id } },
            snackbarHostState = snackbar,
            onPlayNext = { playback.playNext(pick(it)) },
            onAddToQueue = { playback.addToQueue(pick(it)) },
            onAddToPlaylist = { addToPlaylistIds = it.toList() },
            allFavorite = selection.selected.isNotEmpty() && selection.selected.all { byId[it]?.isFavorite == true },
            onFavoriteToggle = { ids, fav ->
                scope.launch { favorites.setSongsFavorite(pick(ids).associate { it.id to it.filePath }, fav) }
            },
            shareItems = { ids -> pick(ids).map { ShareHelper.Item(it.filePath, it.id, it.format?.let { f -> "audio/$f" }) } },
        )
    }
    SongActionsHost(
        state = actions,
        snackbarHostState = snackbar,
        onGoToAlbum = { onNavigate(albumKeyOf(it)) },
        onGoToArtist = { onNavigate(artistKeyOf(it)) },
        onSelect = { selection.onLongPress(it.id) },
    )
    addToPlaylistIds?.let { ids ->
        PlaylistSelectorSheetHost(
            songIds = ids,
            onDismiss = { addToPlaylistIds = null; selection.clear() },
            onMessage = { snack(it) },
        )
    }
}

@Composable
private fun SongItem(
    song: Song,
    subtitle: String,
    isPlaying: Boolean,
    selected: Boolean,
    selectionActive: Boolean,
    snackbar: SnackbarHostState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val fav = stringResource(if (song.isFavorite) R.string.songs_unfavorite else R.string.songs_favorite)
    SongRow(
        title = song.title,
        artist = subtitle,
        durationMs = song.durationMs,
        onClick = onClick,
        artwork = song.artworkPath,
        showArtwork = true,
        isPlaying = isPlaying,
        selected = selected,
        onLongClick = onLongClick,
        accessibilityActions = listOf(SongRowAction(stringResource(R.string.cd_more_options), onLongClick), SongRowAction(fav, onFavorite)),
        swipeActions = rememberQueueSwipeActions(song, snackbar),
        swipeEnabled = !selectionActive,
    )
}

@Composable
private fun SyncBanner(enabled: Boolean, onSyncNow: () -> Unit, onLater: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.songs_banner_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.songs_banner_subtitle), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onLater, enabled = enabled) { Text(stringResource(R.string.songs_banner_remind_later)) }
                TextButton(onClick = onSyncNow, enabled = enabled) { Text(stringResource(R.string.songs_banner_sync_now)) }
            }
        }
    }
}
