package de.yurtemre.sonora.ui.screens.album

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.components.SongRow
import de.yurtemre.sonora.ui.components.SongRowAction
import de.yurtemre.sonora.ui.format.rememberTotalDurationText
import de.yurtemre.sonora.ui.screens.nowplaying.Spine

/** Track count · total time, for detail headers (tabular figures, DESIGN §5). */
@Composable
fun countAndDuration(count: Int, totalMs: Long): String = stringResource(
    R.string.library_count_separator,
    pluralStringResource(R.plurals.track_count, count, count),
    rememberTotalDurationText(totalMs / 1000),
)

/**
 * Detail header with the Spine (DESIGN §5): the title runs bottom-to-top beside the [art],
 * [subtitle] and [meta] sit below, then the play/shuffle group and the favourite toggle.
 */
@Composable
fun SpineHeader(
    title: String,
    subtitle: String?,
    meta: String,
    favorite: Boolean,
    favoriteDescription: String,
    onFavorite: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    art: @Composable (Modifier) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(240.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Spine(title, Modifier.width(72.dp).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                art(Modifier.fillMaxHeight())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                meta,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayShuffleGroup(onPlay, onShuffle, Modifier.weight(1f))
            FavoriteToggle(favorite, favoriteDescription, onFavorite)
        }
    }
}

/** Connected button group: Play (filled) + Shuffle (tonal). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayShuffleGroup(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        Button(
            onClick = onPlay,
            shapes = ButtonShapes(ButtonGroupDefaults.connectedLeadingButtonShape, ButtonGroupDefaults.connectedLeadingButtonPressShape),
            modifier = Modifier.weight(1f).height(48.dp),
        ) {
            Icon(Icons.Rounded.PlayArrow, null)
            Text(stringResource(R.string.library_play), Modifier.padding(start = 8.dp))
        }
        FilledTonalButton(
            onClick = onShuffle,
            shapes = ButtonShapes(ButtonGroupDefaults.connectedTrailingButtonShape, ButtonGroupDefaults.connectedTrailingButtonPressShape),
            modifier = Modifier.weight(1f).height(48.dp),
        ) {
            Icon(Icons.Rounded.Shuffle, null)
            Text(stringResource(R.string.library_shuffle), Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
fun FavoriteToggle(checked: Boolean, description: String, onToggle: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    FilledTonalIconToggleButton(
        checked = checked,
        onCheckedChange = {
            haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onToggle()
        },
    ) {
        Icon(if (checked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, description)
    }
}

/** Plain detail scaffold; the bar title only appears once the Spine header has scrolled away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    showTitle: Boolean,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { if (showTitle) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = bottomBar,
    ) { padding -> content(Modifier.padding(padding)) }
}

/** Song row with an overflow / accessibility menu and an optimistic favourite state. */
@Composable
fun DetailSongRow(
    song: Song,
    subtitle: String,
    isPlaying: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onFavorite: () -> Unit,
    trackNumber: Int? = null,
    showArtwork: Boolean = false,
    onMore: (() -> Unit)? = null,
    selectionActive: Boolean = false,
    snackbar: androidx.compose.material3.SnackbarHostState? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var fav by remember(song.isFavorite) { mutableStateOf(song.isFavorite) }
    val haptics = LocalHapticFeedback.current
    val toggleFav = { fav = !fav; onFavorite() }
    val playNext = stringResource(R.string.library_play_next)
    val addQueue = stringResource(R.string.library_add_to_queue)
    val favLabel = stringResource(if (fav) R.string.library_unfavorite else R.string.library_favorite)
    Box {
        SongRow(
            title = song.title,
            artist = subtitle,
            durationMs = song.durationMs,
            onClick = onClick,
            trackNumber = trackNumber,
            artwork = song.artworkPath,
            showArtwork = showArtwork,
            isPlaying = isPlaying,
            selected = selected,
            // Long-press opens the action sheet; selection starts from its "Select" row.
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (!selectionActive && onMore != null) onMore() else onLongClick()
            },
            onMoreClick = onMore ?: { menu = true },
            accessibilityActions = listOf(SongRowAction(favLabel, toggleFav)),
            swipeActions = de.yurtemre.sonora.ui.components.rememberQueueSwipeActions(song, snackbar),
            swipeEnabled = !selectionActive,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            DropdownMenuItem(text = { Text(playNext) }, onClick = { menu = false; onPlayNext() })
            DropdownMenuItem(text = { Text(addQueue) }, onClick = { menu = false; onAddToQueue() })
            DropdownMenuItem(text = { Text(favLabel) }, onClick = { menu = false; toggleFav() })
        }
    }
}
