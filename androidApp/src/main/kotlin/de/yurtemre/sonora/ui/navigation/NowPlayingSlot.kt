package de.yurtemre.sonora.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.ui.screens.nowplaying.NowPlayingOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Now Playing overlay state owned by the shell (SPEC §6). [progress] is continuous (0 = collapsed, 1 = expanded)
 * so the mini player drag, the overlay drag-down and predictive back all follow the finger; [expanded] is the
 * settled target.
 */
@Stable
class NowPlayingSlotState(initialExpanded: Boolean, private val scope: CoroutineScope) {
    internal val progress = Animatable(if (initialExpanded) 1f else 0f)
    private var target by mutableStateOf(initialExpanded)
    internal var spec: AnimationSpec<Float> = spring()
    /** Height of the overlay in px; drags are normalised against it. */
    internal var heightPx = 1f

    var expanded: Boolean
        get() = target
        set(value) { animateTo(value) }

    /** Predictive back progress 0..1 driving the collapse (DESIGN §4). */
    var backProgress by mutableFloatStateOf(0f)

    /** True while any part of the overlay is on screen. */
    val visible: Boolean by derivedStateOf { target || progress.value > 0f }

    /** Current progress 0..1, for callers that fade other UI (e.g. the mini player) with it. */
    val fraction: Float get() = progress.value

    fun animateTo(expand: Boolean) {
        target = expand
        scope.launch { progress.animateTo(if (expand) 1f else 0f, spec) }
    }

    /** Finger moved by [dyPx] (positive = down = toward collapsed). */
    fun dragBy(dyPx: Float) {
        target = true
        val next = (progress.value - dyPx / heightPx).coerceIn(0f, 1f)
        scope.launch { progress.snapTo(next) }
    }

    /** Finger lifted with [velocityPx] per second (positive = down). Flings win; otherwise the nearer end. */
    fun settle(velocityPx: Float, flingThresholdPx: Float) {
        val expand = when {
            velocityPx < -flingThresholdPx -> true
            velocityPx > flingThresholdPx -> false
            else -> progress.value > 0.5f
        }
        target = expand
        scope.launch { progress.animateTo(if (expand) 1f else 0f, spec, initialVelocity = -velocityPx / heightPx) }
    }
}

private const val BACK_SCALE = 0.06f
private const val BACK_TRAVEL = 0.12f
private val BackCorner = 28.dp
private val FlingThreshold = 600.dp

/** Draggable modifier that lets the user pull the overlay down (or the mini player up) with the finger. */
@Composable
fun Modifier.nowPlayingDrag(state: NowPlayingSlotState, enabled: Boolean = true): Modifier {
    val fling = with(LocalDensity.current) { FlingThreshold.toPx() }
    return draggable(
        state = rememberDraggableState { state.dragBy(it) },
        orientation = Orientation.Vertical,
        enabled = enabled,
        onDragStopped = { v -> state.settle(v, fling) },
    )
}

/**
 * Full-screen Now Playing overlay above the shell (P5-07, P5-25). It is translated by the continuous slot
 * progress inside a graphics layer, so following the finger never recomposes the player. Predictive back pulls
 * the overlay down a little, scales it toward 0.94 / 28 dp corners and collapses it on commit.
 */
@Composable
fun NowPlayingSlot(
    state: NowPlayingSlotState,
    modifier: Modifier = Modifier,
    snackbarHostState: androidx.compose.material3.SnackbarHostState? = null,
    onNavigate: (NavKey) -> Unit = {},
) {
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    SideEffect { state.spec = spec }
    PredictiveBackHandler(enabled = state.expanded) { progress ->
        try {
            progress.collect { state.backProgress = it.progress }
            state.expanded = false
        } catch (e: CancellationException) {
            throw e
        } finally {
            state.backProgress = 0f
        }
    }
    if (!state.visible) return
    val collapse = { state.expanded = false }
    NowPlayingOverlay(
        onCollapse = collapse,
        onOpenAlbum = { collapse(); onNavigate(Album(it.album, it.albumArtist ?: it.artist)) },
        onOpenArtist = { collapse(); onNavigate(Artist(it.artist)) },
        snackbarHostState = snackbarHostState,
        dragModifier = Modifier.nowPlayingDrag(state),
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { state.heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                val b = state.backProgress
                val p = state.fraction
                translationY = (1f - p + BACK_TRAVEL * b) * size.height
                val s = 1f - BACK_SCALE * b
                scaleX = s
                scaleY = s
                shape = RoundedCornerShape(BackCorner * maxOf(b, 1f - p).coerceIn(0f, 1f))
                clip = b > 0f || p < 1f
            },
    )
}

@Composable
fun rememberNowPlayingSlotState(): NowPlayingSlotState {
    var saved by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state = remember { NowPlayingSlotState(saved, scope) }
    SideEffect { saved = state.expanded }
    return state
}
