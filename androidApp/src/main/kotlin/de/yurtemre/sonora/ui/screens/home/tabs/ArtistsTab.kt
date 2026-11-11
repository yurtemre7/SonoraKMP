package de.yurtemre.sonora.ui.screens.home.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.library.ScrollSection
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.ui.components.ArtistAvatar
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.FastScroller
import de.yurtemre.sonora.ui.components.binLabel
import de.yurtemre.sonora.ui.components.binStarts
import de.yurtemre.sonora.ui.components.binsOf
import de.yurtemre.sonora.ui.components.countBand
import de.yurtemre.sonora.ui.components.letterBin
import de.yurtemre.sonora.shared.model.ArtistSort
import androidx.compose.runtime.remember
import de.yurtemre.sonora.ui.components.SectionHeader
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.screens.home.HomeTabReselect
import de.yurtemre.sonora.ui.screens.home.HomeTabState
import org.koin.compose.koinInject

/** Artists tab (UI-10): cookie avatars, album/song counts, tap → Artist. */
@Composable
fun ArtistsTab(state: HomeTabState, onOpen: (ArtistGroup) -> Unit) {
    val playback = koinInject<PlaybackConnection>()
    val listState = rememberLazyListState()
    val artists = state.artists

    LaunchedEffect(listState) {
        HomeTabReselect.events.collect { if (it == LibraryTab.Artists) listState.animateScrollToItem(0) }
    }

    when {
        !state.loaded -> Unit
        state.totalCount == 0 -> EmptyState(
            icon = Icons.Rounded.Person,
            title = stringResource(R.string.library_artists_empty),
            subtitle = stringResource(R.string.library_library_empty_subtitle),
            modifier = Modifier.fillMaxSize(),
        )
        artists.isEmpty() -> EmptyState(
            icon = Icons.Rounded.SearchOff,
            title = stringResource(R.string.library_artists_no_match),
            subtitle = stringResource(R.string.library_no_match_subtitle, state.query.trim()),
            modifier = Modifier.fillMaxSize(),
        )
        else -> Box(Modifier.fillMaxSize()) {
            val sort = ArtistSort.fromStorage(state.sort.key)
            val bins = remember(artists, sort) {
                binsOf(artists) {
                    when (sort) {
                        ArtistSort.Albums -> countBand(it.albums.size)
                        ArtistSort.Songs -> countBand(it.songs.size)
                        else -> letterBin(it.name)
                    }
                }
            }
            val labels = bins.map { binLabel(it.key) }
            val starts = remember(bins) { binStarts(bins) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                bins.forEach { bin ->
                    items(bin.items, key = { it.key() }, contentType = { "artist" }) { artist -> ArtistRow(artist) { onOpen(artist) } }
                }
            }
            FastScroller(listState, labels, starts, Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun ArtistRow(artist: ArtistGroup, onClick: () -> Unit) {
    val counts = stringResource(
        R.string.library_count_separator,
        pluralStringResource(R.plurals.album_count, artist.albums.size, artist.albums.size),
        pluralStringResource(R.plurals.song_count, artist.songs.size, artist.songs.size),
    )
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            ArtistAvatar(artist.localImagePath ?: artist.songs.firstNotNullOfOrNull { it.artworkPath }, artist.name, Modifier.size(56.dp))
        },
        headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(counts, maxLines = 1) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
    )
}
