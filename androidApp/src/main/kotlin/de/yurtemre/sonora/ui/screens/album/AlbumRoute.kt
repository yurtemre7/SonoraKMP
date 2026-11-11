package de.yurtemre.sonora.ui.screens.album

import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.ui.navigation.artistKeyOf
import de.yurtemre.sonora.ui.navigation.albumKeyOf
import de.yurtemre.sonora.ui.sheets.rememberSongActionsState
import de.yurtemre.sonora.ui.sheets.SongActionsHost
import de.yurtemre.sonora.ui.sheets.PlaylistSelectorSheetHost
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.platform.ShareHelper
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.library.AlbumGrouper
import de.yurtemre.sonora.shared.library.TrackNumbers
import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.ArtSize
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.MultiSelectActionBar
import de.yurtemre.sonora.ui.components.SectionHeader
import de.yurtemre.sonora.ui.components.SelectionState
import de.yurtemre.sonora.ui.components.rememberSelectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** Album detail (UI-23): Spine header, play/shuffle, favourite, tracks by disc, menus, multi-select. */
@Composable
fun AlbumRoute(name: String, artist: String, onBack: () -> Unit, onNavigate: (NavKey) -> Unit = {}) {
    val actions = rememberSongActionsState()
    val library = koinInject<LibraryRepository>()
    val favorites = koinInject<FavoritesRepository>()
    val playback = koinInject<PlaybackConnection>()
    val songs by library.songs.collectAsStateWithLifecycle(null)
    val favAlbums by favorites.favoriteAlbums.collectAsStateWithLifecycle(emptyMap())
    val player by playback.player.collectAsStateWithLifecycle()
    val album by produceState<AlbumGroup?>(null, songs, name, artist) {
        val all = songs ?: return@produceState
        value = withContext(Dispatchers.Default) {
            AlbumGrouper.find(name, artist, all).let { AlbumGroup(it.name, it.artist, AlbumGrouper.sortTracks(it.songs)) }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val selection = rememberSelectionState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    BackHandler(selection.active) { selection.clear() }

    val a = album
    val tracks = a?.songs.orEmpty()
    fun snack(id: Int) = scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(res.getString(id)) }

    // Optimistic favourite: flips locally at once, reconciles when the repository emits.
    val actualFav = a != null && favAlbums.containsKey(a.key())
    var favOverride by remember(actualFav) { mutableStateOf<Boolean?>(null) }

    DetailScaffold(
        title = a?.name ?: name,
        showTitle = showTitle,
        onBack = onBack,
        snackbar = snackbar,
        bottomBar = { if (selection.active) DetailSelectionBar(selection, tracks, snackbar) },
    ) { modifier ->
        when {
            a == null -> Unit
            tracks.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Album,
                title = stringResource(R.string.library_album_not_found),
                subtitle = stringResource(R.string.library_not_found_subtitle),
                modifier = modifier.fillMaxSize(),
            )
            else -> {
                val discs = remember(tracks) { tracks.groupBy { it.discNumber ?: TrackNumbers.split(it.trackNumber).first ?: 1 } }
                LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
                    item(key = "header") {
                        SpineHeader(
                            title = a.name,
                            subtitle = a.artist,
                            meta = countAndDuration(tracks.size, tracks.sumOf { it.durationMs }),
                            favorite = favOverride ?: actualFav,
                            favoriteDescription = stringResource(R.string.library_cd_favorite_album),
                            onFavorite = {
                                favOverride = !(favOverride ?: actualFav)
                                scope.launch { favorites.toggleAlbum(a.key()) }
                            },
                            onPlay = { playback.playSong(tracks.first(), tracks) },
                            onShuffle = { playback.playShuffled(tracks) },
                            art = { m ->
                                AlbumArt(tracks.firstNotNullOfOrNull { it.artworkPath }, a.name, m.aspectRatio(1f), ArtSize.Large, artist = a.artist)
                            },
                        )
                    }
                    discs.forEach { (disc, discTracks) ->
                        if (discs.size > 1) item(key = "disc-$disc") {
                            SectionHeader(stringResource(R.string.library_disc, disc), count = discTracks.size)
                        }
                        items(discTracks, key = { it.id }) { song ->
                            DetailSongRow(
                                song = song,
                                subtitle = song.artist,
                                trackNumber = TrackNumbers.split(song.trackNumber).second,
                                isPlaying = player.currentSong?.id == song.id,
                                selected = selection.isSelected(song.id),
                                onClick = { if (!selection.onTap(song.id)) playback.playSong(song, tracks) },
                                onLongClick = { selection.onLongPress(song.id) },
                                onPlayNext = { playback.playNext(listOf(song)); snack(R.string.library_snack_play_next) },
                                onAddToQueue = { playback.addToQueue(listOf(song)); snack(R.string.library_snack_queued) },
                                onFavorite = { scope.launch { favorites.toggleSong(song.id, song.filePath) } },
                                onMore = { actions.show(song) },
                                selectionActive = selection.active,
                                snackbar = snackbar,
                            )
                        }
                    }
                }
            }
        }
    }
    SongActionsHost(
        state = actions,
        snackbarHostState = snackbar,
        onSelect = { selection.onLongPress(it.id) },
        onGoToAlbum = { onNavigate(albumKeyOf(it)) },
        onGoToArtist = { onNavigate(artistKeyOf(it)) },
    )
    LaunchedEffect(tracks) {
        val ids = tracks.mapTo(HashSet()) { it.id }
        selection.selected.filter { it !in ids }.forEach(selection::toggle)
    }
}

/** Multi-select bar shared by album and artist detail; Add to playlist opens the playlist selector. */
@Composable
internal fun DetailSelectionBar(selection: SelectionState, songs: List<Song>, snackbar: SnackbarHostState) {
    val playback = koinInject<PlaybackConnection>()
    val favorites = koinInject<FavoritesRepository>()
    val scope = rememberCoroutineScope()
    val byId = remember(songs) { songs.associateBy { it.id } }
    fun pick(ids: Set<Long>) = songs.filter { it.id in ids }
    var addToPlaylistIds by remember { mutableStateOf<List<Long>?>(null) }
    addToPlaylistIds?.let { ids ->
        PlaylistSelectorSheetHost(
            songIds = ids,
            onDismiss = { addToPlaylistIds = null; selection.clear() },
            onMessage = { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } },
        )
    }
    MultiSelectActionBar(
        selection = selection,
        allIds = { songs.map { it.id } },
        snackbarHostState = snackbar,
        onPlayNext = { playback.playNext(pick(it)) },
        onAddToQueue = { playback.addToQueue(pick(it)) },
        onAddToPlaylist = { addToPlaylistIds = it.toList() },
        allFavorite = selection.selected.isNotEmpty() && selection.selected.all { byId[it]?.isFavorite == true },
        onFavoriteToggle = { ids, fav -> scope.launch { favorites.setSongsFavorite(pick(ids).associate { it.id to it.filePath }, fav) } },
        shareItems = { ids -> pick(ids).map { ShareHelper.Item(it.filePath, it.id, it.format?.let { f -> "audio/$f" }) } },
    )
}
