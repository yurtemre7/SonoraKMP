package de.yurtemre.sonora.ui.screens.home.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.library.AlbumGrouper
import de.yurtemre.sonora.shared.library.ScrollSection
import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.AlbumSort
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.ArtSize
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.BinDivider
import de.yurtemre.sonora.ui.components.BinIndexStrip
import de.yurtemre.sonora.ui.components.addedBin
import de.yurtemre.sonora.ui.components.binLabel
import de.yurtemre.sonora.ui.components.binStarts
import de.yurtemre.sonora.ui.components.binsOf
import de.yurtemre.sonora.ui.components.countBand
import de.yurtemre.sonora.ui.components.letterBin
import de.yurtemre.sonora.ui.components.SectionHeader
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.screens.home.HomeTabReselect
import de.yurtemre.sonora.ui.screens.home.HomeTabState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Albums tab (UI-09): adaptive sleeve grid, fast scroller, long-press menu. */
@Composable
fun AlbumsTab(state: HomeTabState, snackbar: SnackbarHostState, onOpen: (AlbumGroup) -> Unit) {
    val playback = koinInject<PlaybackConnection>()
    val favorites = koinInject<FavoritesRepository>()
    val favAlbums by favorites.favoriteAlbums.collectAsStateWithLifecycle(emptyMap())
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    val albums = state.albums
    val sort = AlbumSort.fromStorage(state.sort.key)
    fun snack(id: Int) = scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(res.getString(id)) }

    LaunchedEffect(gridState) {
        HomeTabReselect.events.collect { if (it == LibraryTab.Albums) gridState.animateScrollToItem(0) }
    }

    when {
        !state.loaded -> Unit
        state.totalCount == 0 -> EmptyState(
            icon = Icons.Rounded.Album,
            title = stringResource(R.string.library_albums_empty),
            subtitle = stringResource(R.string.library_library_empty_subtitle),
            modifier = Modifier.fillMaxSize(),
        )
        albums.isEmpty() -> EmptyState(
            icon = Icons.Rounded.SearchOff,
            title = stringResource(R.string.library_albums_no_match),
            subtitle = stringResource(R.string.library_no_match_subtitle, state.query.trim()),
            modifier = Modifier.fillMaxSize(),
        )
        else -> Box(Modifier.fillMaxSize()) {
            val bins = remember(albums, sort) {
                binsOf(albums) {
                    when (sort) {
                        AlbumSort.Artist -> letterBin(it.artist)
                        AlbumSort.Tracks -> countBand(it.songs.size)
                        AlbumSort.Recent -> addedBin(it.songs.maxOfOrNull { s -> s.dateAddedMs ?: s.lastModifiedMs ?: 0L })
                        else -> letterBin(it.name)
                    }
                }
            }
            val labels = bins.map { binLabel(it.key) }
            val starts = remember(bins) { binStarts(bins) }
            // 160 dp minimum → 2 columns on phones, 3–4 on medium widths (DESIGN §7).
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                state = gridState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                bins.forEachIndexed { bi, bin ->
                stickyHeader(key = "bin-$bi", contentType = "bin") { BinDivider(labels[bi], Modifier.bleed(16.dp)) }
                items(bin.items, key = { it.key() }) { album ->
                    val tracks = remember(album) { AlbumGrouper.sortTracks(album.songs) }
                    val fav = favAlbums.containsKey(album.key())
                    AlbumCell(
                        album = album,
                        favorite = fav,
                        onClick = { onOpen(album) },
                        onPlay = { playback.playSong(tracks.first(), tracks) },
                        onShuffle = { playback.playShuffled(tracks) },
                        onPlayNext = { playback.playNext(tracks); snack(R.string.library_snack_play_next) },
                        onAddToQueue = { playback.addToQueue(tracks); snack(R.string.library_snack_queued) },
                        onFavorite = { scope.launch { favorites.toggleAlbum(album.key()) } },
                    )
                }
                }
            }
            BinIndexStrip(gridState, labels, starts, Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** Cancels the grid's horizontal content padding so full-span headers align with list screens. */
private fun Modifier.bleed(h: Dp): Modifier = layout { m, c ->
    val extra = h.roundToPx() * 2
    val p = m.measure(c.copy(minWidth = c.minWidth + extra, maxWidth = c.maxWidth + extra))
    layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCell(
    album: AlbumGroup,
    favorite: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onFavorite: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // Optimistic: the menu label flips at once; the repository flow reconciles.
    var fav by remember(favorite) { mutableStateOf(favorite) }
    val haptics = LocalHapticFeedback.current
    val toggleFav = { fav = !fav; onFavorite() }
    val play = stringResource(R.string.library_play)
    val shuffle = stringResource(R.string.library_shuffle)
    val playNext = stringResource(R.string.library_play_next)
    val addQueue = stringResource(R.string.library_add_to_queue)
    val favLabel = stringResource(if (fav) R.string.library_unfavorite else R.string.library_favorite)
    val count = pluralStringResource(R.plurals.track_count, album.songs.size, album.songs.size)
    Box {
        Column(
            Modifier
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                )
                .semantics(mergeDescendants = true) {
                    customActions = listOf(
                        CustomAccessibilityAction(play) { onPlay(); true },
                        CustomAccessibilityAction(shuffle) { onShuffle(); true },
                        CustomAccessibilityAction(playNext) { onPlayNext(); true },
                        CustomAccessibilityAction(addQueue) { onAddToQueue(); true },
                        CustomAccessibilityAction(favLabel) { toggleFav(); true },
                    )
                },
        ) {
            AlbumArt(
                model = album.songs.firstNotNullOfOrNull { it.artworkPath },
                title = album.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                size = ArtSize.Medium,
                decorative = true,
                artist = album.artist,
            )
            Text(
                album.name,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.library_count_separator, album.artist, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(play) }, onClick = { menu = false; onPlay() })
            DropdownMenuItem(text = { Text(shuffle) }, onClick = { menu = false; onShuffle() })
            DropdownMenuItem(text = { Text(playNext) }, onClick = { menu = false; onPlayNext() })
            DropdownMenuItem(text = { Text(addQueue) }, onClick = { menu = false; onAddToQueue() })
            DropdownMenuItem(text = { Text(favLabel) }, onClick = { menu = false; toggleFav() })
        }
    }
}
