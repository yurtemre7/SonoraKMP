package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.library.JumpBackItem
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.theme.SleeveShape

/** Up to three recently played albums plus a See all tile: tap opens the album, long press or the small play button plays it. */
@Composable
fun JumpBackInRow(
    items: List<JumpBackItem>,
    onOpen: (JumpBackItem) -> Unit,
    onPlay: (JumpBackItem) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.take(3).forEach { item -> JumpBackTile(item, onOpen, onPlay, Modifier.weight(1f)) }
        // The next slot opens the full list; empty slots keep tile widths stable when fewer than three albums exist.
        SeeAllTile(onSeeAll, Modifier.weight(1f).aspectRatio(1f))
        repeat((3 - items.size).coerceAtLeast(0)) { Box(Modifier.weight(1f)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JumpBackTile(item: JumpBackItem, onOpen: (JumpBackItem) -> Unit, onPlay: (JumpBackItem) -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    val playLabel = stringResource(R.string.home_jump_play_cd, item.albumName)
    val play = {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onPlay(item)
    }
    Column(
        modifier
            .clip(SleeveShape)
            .combinedClickable(
                onClick = { onOpen(item) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPlay(item)
                },
                onLongClickLabel = playLabel,
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            AlbumArt(
                item.coverSong.artworkPath,
                item.albumName,
                Modifier.fillMaxWidth().aspectRatio(1f),
                shape = SleeveShape,
                decorative = true,
                artist = item.albumArtist,
            )
            Surface(
                onClick = play,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(32.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, playLabel, Modifier.size(20.dp)) }
            }
        }
        Text(item.albumName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            item.albumArtist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
