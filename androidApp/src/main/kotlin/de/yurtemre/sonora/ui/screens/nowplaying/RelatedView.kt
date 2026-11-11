package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.SectionHeader
import de.yurtemre.sonora.ui.components.SongRow
import org.koin.compose.koinInject

/**
 * Related (UI-32): "From this album" and "More by this artist", both excluding the current song.
 * Tapping a row plays it within that section's list.
 */
@Composable
fun RelatedView(
    song: Song,
    onPlay: (song: Song, list: List<Song>) -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
    modifier: Modifier = Modifier,
    library: LibraryRepository = koinInject(),
) {
    val songs by library.songs.collectAsState(initial = emptyList())
    val haptics = LocalHapticFeedback.current
    val (album, artist) = remember(songs, song.id) {
        val albumSongs = songs.filter {
            it.id != song.id && if (song.albumId != null) it.albumId == song.albumId
            else it.albumLower == song.albumLower && it.artistLower == song.artistLower
        }.sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.titleLower }))
        val albumIds = albumSongs.mapTo(HashSet()) { it.id }
        val artistSongs = songs.filter { it.id != song.id && it.artistLower == song.artistLower && it.id !in albumIds }
            .sortedBy { it.titleLower }
        albumSongs to artistSongs
    }
    if (album.isEmpty() && artist.isEmpty()) {
        EmptyState(icon = Icons.Outlined.Album, title = stringResource(R.string.related_empty), modifier = modifier.fillMaxSize())
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        if (album.isNotEmpty()) {
            item(key = "h-album") {
                SectionHeader(stringResource(R.string.related_from_album), count = album.size, trailing = {
                    IconButton(onClick = onOpenAlbum) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.related_open_album))
                    }
                })
            }
            items(album, key = { "a-${it.id}" }) { s ->
                SongRow(
                    title = s.title, artist = s.artist, durationMs = s.durationMs, trackNumber = s.trackNumber,
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onPlay(s, album) },
                    swipeActions = de.yurtemre.sonora.ui.components.rememberQueueSwipeActions(s, null),
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (artist.isNotEmpty()) {
            item(key = "h-artist") {
                SectionHeader(stringResource(R.string.related_more_by_artist), count = artist.size, trailing = {
                    IconButton(onClick = onOpenArtist) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.related_open_artist))
                    }
                })
            }
            items(artist, key = { "r-${it.id}" }) { s ->
                SongRow(
                    title = s.title, artist = s.album, durationMs = s.durationMs, artwork = s.artworkPath, showArtwork = true,
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onPlay(s, artist) },
                    swipeActions = de.yurtemre.sonora.ui.components.rememberQueueSwipeActions(s, null),
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}
