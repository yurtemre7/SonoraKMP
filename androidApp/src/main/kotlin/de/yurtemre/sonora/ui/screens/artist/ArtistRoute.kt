package de.yurtemre.sonora.ui.screens.artist

import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.ui.components.preferredArtistImage
import de.yurtemre.sonora.ui.navigation.artistKeyOf
import de.yurtemre.sonora.ui.navigation.albumKeyOf
import de.yurtemre.sonora.ui.sheets.rememberSongActionsState
import de.yurtemre.sonora.ui.sheets.SongActionsHost
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.library.AlbumGrouper
import de.yurtemre.sonora.shared.library.ArtistGrouper
import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.ArtSize
import de.yurtemre.sonora.ui.components.ArtistAvatar
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.SectionHeader
import de.yurtemre.sonora.ui.components.rememberSelectionState
import de.yurtemre.sonora.ui.format.rememberTotalDurationText
import de.yurtemre.sonora.ui.screens.album.DetailScaffold
import de.yurtemre.sonora.ui.screens.album.DetailSelectionBar
import de.yurtemre.sonora.ui.screens.album.DetailSongRow
import de.yurtemre.sonora.ui.screens.album.SpineHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private data class ArtistDetail(val artist: ArtistGroup, val songs: List<Song>)

/**
 * Artist detail (UI-24): avatar header, play/shuffle, favourite, albums carousel, most played, all songs.
 * [onOpenAlbum] is wired by the app shell; without it the carousel is display-only.
 */
@Composable
fun ArtistRoute(
    name: String,
    onBack: () -> Unit,
    onOpenAlbum: ((name: String, artist: String) -> Unit)? = null,
    onNavigate: (NavKey) -> Unit = {},
) {
    val actions = rememberSongActionsState()
    val library = koinInject<LibraryRepository>()
    val favorites = koinInject<FavoritesRepository>()
    val playback = koinInject<PlaybackConnection>()
    val stats = koinInject<StatsRepository>()
    val songs by library.songs.collectAsStateWithLifecycle(null)
    val images by library.artistImages.collectAsStateWithLifecycle(emptyMap())
    val favArtists by favorites.favoriteArtists.collectAsStateWithLifecycle(emptyMap())
    val snapshot by stats.snapshot.collectAsStateWithLifecycle(null)
    val player by playback.player.collectAsStateWithLifecycle()
    val detail by produceState<ArtistDetail?>(null, songs, images, name) {
        val all = songs ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val g = ArtistGrouper.find(name, all, images)
            ArtistDetail(g, g.albums.flatMap { AlbumGrouper.sortTracks(it.songs) }.ifEmpty { g.songs })
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val selection = rememberSelectionState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    BackHandler(selection.active) { selection.clear() }
    fun snack(id: Int) = scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(res.getString(id)) }

    val d = detail
    val tracks = d?.songs.orEmpty()
    val top = remember(tracks, snapshot) {
        val plays = snapshot?.songs ?: emptyMap()
        tracks.filter { (plays[it.id]?.plays ?: 0) > 0 }.sortedByDescending { plays[it.id]?.plays ?: 0 }.take(5)
    }
    val actualFav = d != null && favArtists.containsKey(d.artist.key())
    var favOverride by remember(actualFav) { mutableStateOf<Boolean?>(null) }

    DetailScaffold(
        title = d?.artist?.name ?: name,
        showTitle = showTitle,
        onBack = onBack,
        snackbar = snackbar,
        bottomBar = { if (selection.active) DetailSelectionBar(selection, tracks, snackbar) },
    ) { modifier ->
        when {
            d == null -> Unit
            tracks.isEmpty() -> EmptyState(
                icon = Icons.Rounded.PersonOff,
                title = stringResource(R.string.library_artist_not_found),
                subtitle = stringResource(R.string.library_not_found_subtitle),
                modifier = modifier.fillMaxSize(),
            )
            else -> LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
                val a = d.artist
                item(key = "header") {
                    val meta = listOf(
                        pluralStringResource(R.plurals.album_count, a.albums.size, a.albums.size),
                        pluralStringResource(R.plurals.song_count, tracks.size, tracks.size),
                        rememberTotalDurationText(tracks.sumOf { it.durationMs } / 1000),
                    ).joinToString(" · ")
                    SpineHeader(
                        title = a.name,
                        subtitle = null,
                        meta = meta,
                        favorite = favOverride ?: actualFav,
                        favoriteDescription = stringResource(R.string.library_cd_favorite_artist),
                        onFavorite = {
                            favOverride = !(favOverride ?: actualFav)
                            scope.launch { favorites.toggleArtist(a.key()) }
                        },
                        onPlay = { playback.playSong(tracks.first(), tracks) },
                        onShuffle = { playback.playShuffled(tracks) },
                        art = { m ->
                            ArtistAvatar(preferredArtistImage(a.localImagePath) ?: tracks.firstNotNullOfOrNull { it.artworkPath }, a.name, m.aspectRatio(1f), ArtSize.Large)
                        },
                    )
                }
                if (a.albums.isNotEmpty()) {
                    item(key = "albums-header") { SectionHeader(stringResource(R.string.library_artist_albums), count = a.albums.size) }
                    item(key = "albums") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(a.albums, key = { it.key() }) { album -> AlbumCard(album) { onOpenAlbum?.invoke(album.name, album.artist) } }
                        }
                    }
                }
                if (top.isNotEmpty()) {
                    item(key = "top-header") { SectionHeader(stringResource(R.string.library_artist_top_songs)) }
                    items(top, key = { "top-${it.id}" }) { song ->
                        DetailSongRow(
                            song = song, subtitle = song.album, showArtwork = true,
                            isPlaying = player.currentSong?.id == song.id,
                            selected = selection.isSelected(song.id),
                            onClick = { if (!selection.onTap(song.id)) playback.playSong(song, top) },
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
                item(key = "all-header") { SectionHeader(stringResource(R.string.library_artist_all_songs), count = tracks.size) }
                items(tracks, key = { it.id }) { song ->
                    DetailSongRow(
                        song = song, subtitle = song.album, showArtwork = true,
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
    SongActionsHost(
        state = actions,
        snackbarHostState = snackbar,
        onSelect = { selection.onLongPress(it.id) },
        onGoToAlbum = { onNavigate(albumKeyOf(it)) },
        onGoToArtist = { onNavigate(artistKeyOf(it)) },
    )
}

@Composable
private fun AlbumCard(album: AlbumGroup, onClick: () -> Unit) {
    Column(
        Modifier.width(140.dp).combinedClickable(role = Role.Button, onClick = onClick),
    ) {
        AlbumArt(album.songs.firstNotNullOfOrNull { it.artworkPath }, album.name, Modifier.size(140.dp), ArtSize.Medium, artist = album.artist, decorative = true)
        Text(album.name, Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            pluralStringResource(R.plurals.track_count, album.songs.size, album.songs.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
