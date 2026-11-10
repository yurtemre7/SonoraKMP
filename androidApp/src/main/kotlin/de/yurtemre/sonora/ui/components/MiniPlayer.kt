package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlayerUiState
import de.yurtemre.sonora.ui.theme.LocalReduceMotion
import kotlin.math.abs

/**
 * Mini player (DESIGN §6.4, SPEC UI-15): flat surfaceContainerHigh bar with a 2dp Cobalt progress line on top.
 * Gestures: tap/swipe up = open, swipe down = close, swipe left = next, swipe right = previous.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    progress: () -> Float,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = state.currentSong ?: return
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val reduce = LocalReduceMotion.current
    val cbOpen by rememberUpdatedState(onOpen)
    val cbNext by rememberUpdatedState(onNext)
    val cbPrev by rememberUpdatedState(onPrevious)
    val cbClose by rememberUpdatedState(onClose)
    var drag by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val primary = MaterialTheme.colorScheme.primary
    val desc = stringResource(R.string.miniplayer_description, song.title, song.artist)
    val prevLabel = stringResource(R.string.miniplayer_action_previous)
    val closeLabel = stringResource(R.string.miniplayer_action_close)
    val openLabel = stringResource(R.string.miniplayer_open)

    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .drawBehind {
                val h = 2.dp.toPx()
                drawRect(primary, Offset.Zero, Size(size.width * progress().coerceIn(0f, 1f), h))
            }
            .pointerInput(Unit) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        when {
                            abs(dy) > abs(dx) && dy < -threshold -> cbOpen()
                            abs(dy) > abs(dx) && dy > threshold -> cbClose()
                            abs(dx) > abs(dy) && dx < -threshold -> cbNext()
                            abs(dx) > abs(dy) && dx > threshold -> cbPrev()
                        }
                        drag = 0f; dragX = 0f
                    },
                    onDragCancel = { drag = 0f; dragX = 0f },
                ) { change, amount ->
                    change.consume()
                    dx += amount.x; dy += amount.y
                    if (!reduce) { dragX = dx * 0.3f; drag = dy.coerceAtLeast(0f) * 0.3f }
                }
            }
            .graphicsLayer { translationX = dragX; translationY = drag }
            .clickable(onClickLabel = openLabel, onClick = onOpen)
            .semantics(mergeDescendants = false) {
                contentDescription = desc
                customActions = listOf(
                    CustomAccessibilityAction(prevLabel) { cbPrev(); true },
                    CustomAccessibilityAction(closeLabel) { cbClose(); true },
                )
            }
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(song.artworkPath, song.title, Modifier.size(44.dp), decorative = true, artist = song.artist)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            PulseButton(state.isPlaying, onTogglePlay, size = 48.dp, isCompleted = state.isCompleted)
        }
        IconButton(onClick = onNext, enabled = state.hasNext, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.SkipNext, stringResource(R.string.miniplayer_next))
        }
    }
}
