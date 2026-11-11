package de.yurtemre.sonora.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Mini player (DESIGN §6.4, SPEC UI-15): flat surfaceContainerHigh bar with a 2dp Cobalt progress line on top.
 * Gestures (P5-25): dragging up hands the finger to the Now Playing slot ([onExpandDrag] / [onExpandDragStopped]),
 * so the overlay rises with it; dragging down past the threshold closes the player; swiping left/right slides
 * the bar with the finger and skips on release. [expandProgress] fades the bar while Now Playing covers it.
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
    expandProgress: () -> Float = { 0f },
    onExpandDrag: (Float) -> Unit = {},
    onExpandDragStopped: (Float) -> Unit = {},
) {
    val song = state.currentSong ?: return
    val density = LocalDensity.current
    val threshold = with(density) { 56.dp.toPx() }
    val fling = with(density) { 800.dp.toPx() }
    val reduce = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val cbNext by rememberUpdatedState(onNext)
    val cbPrev by rememberUpdatedState(onPrevious)
    val cbClose by rememberUpdatedState(onClose)
    val cbDrag by rememberUpdatedState(onExpandDrag)
    val cbDragStop by rememberUpdatedState(onExpandDragStopped)
    val canNext by rememberUpdatedState(state.hasNext)
    val canPrev by rememberUpdatedState(state.hasPrevious)
    val offsetX = remember { Animatable(0f) }
    var closeDrag by remember { mutableFloatStateOf(0f) }
    var verticalTotal by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    /** +1 = next (content enters from the end), −1 = previous. */
    var direction by remember { mutableStateOf(1) }
    val spring = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val slideSpec = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val primary = MaterialTheme.colorScheme.primary
    val desc = stringResource(R.string.miniplayer_description, song.title, song.artist)
    val prevLabel = stringResource(R.string.miniplayer_action_previous)
    val closeLabel = stringResource(R.string.miniplayer_action_close)
    val openLabel = stringResource(R.string.miniplayer_open)

    val horizontal = rememberDraggableState { dx ->
        scope.launch { offsetX.snapTo(offsetX.value + dx) }
    }
    val vertical = rememberDraggableState { dy ->
        verticalTotal += dy
        if (verticalTotal < 0f) {
            closeDrag = 0f
            cbDrag(dy)
        } else if (!reduce) {
            closeDrag = verticalTotal * 0.5f
        }
    }

    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                val p = expandProgress()
                alpha = (1f - p * 2f).coerceIn(0f, 1f)
                translationY = closeDrag
            }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .drawBehind {
                val h = 2.dp.toPx()
                drawRect(primary, Offset.Zero, Size(size.width * progress().coerceIn(0f, 1f), h))
            }
            .draggable(
                state = vertical,
                orientation = Orientation.Vertical,
                onDragStarted = { verticalTotal = 0f },
                onDragStopped = { v ->
                    if (verticalTotal > threshold || (verticalTotal > 0f && v > fling)) {
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        cbClose()
                    } else if (verticalTotal < 0f) {
                        cbDragStop(v)
                    }
                    closeDrag = 0f
                    verticalTotal = 0f
                },
            )
            .draggable(
                state = horizontal,
                orientation = Orientation.Horizontal,
                onDragStopped = { v ->
                    val dx = offsetX.value
                    val goNext = (dx < -threshold || v < -fling) && canNext
                    val goPrev = (dx > threshold || v > fling) && canPrev
                    if (goNext || goPrev) {
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        direction = if (goNext) 1 else -1
                        if (goNext) cbNext() else cbPrev()
                    }
                    offsetX.animateTo(0f, spring, initialVelocity = v)
                },
            )
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
        // Only the song block follows the horizontal finger; the controls stay put under the thumb.
        AnimatedContent(
            targetState = song,
            contentKey = { it.id },
            modifier = Modifier.weight(1f).graphicsLayer {
                translationX = offsetX.value
                alpha = 1f - (abs(offsetX.value) / width).coerceIn(0f, 0.6f)
            },
            transitionSpec = {
                val d = direction
                (slideInHorizontally(slideSpec) { it / 3 * d } + fadeIn()) togetherWith
                    (slideOutHorizontally(slideSpec) { -it / 3 * d } + fadeOut())
            },
            label = "mini-song",
        ) { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(s.artworkPath, s.title, Modifier.size(44.dp), decorative = true, artist = s.artist)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        s.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        PulseButton(state.isPlaying, onTogglePlay, Modifier.padding(horizontal = 4.dp), size = 48.dp, isCompleted = state.isCompleted)
        IconButton(onClick = { direction = 1; onNext() }, enabled = state.hasNext, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.SkipNext, stringResource(R.string.miniplayer_next))
        }
    }
}
