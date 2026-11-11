package de.yurtemre.sonora.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.ui.screens.nowplaying.NowPlayingOverlay
import kotlinx.coroutines.CancellationException

/** Now Playing overlay state owned by the shell (SPEC §6). Collapsed by default. */
@Stable
class NowPlayingSlotState(initialExpanded: Boolean = false) {
    var expanded by mutableStateOf(initialExpanded)
    /** Predictive back progress 0..1 driving the collapse (DESIGN §4). */
    var backProgress by mutableFloatStateOf(0f)
}

private const val BACK_SCALE = 0.06f
private val BackCorner = 28.dp

/**
 * Full-screen Now Playing overlay above the shell (P5-07). Back rule 4: when expanded, predictive back
 * scales the overlay toward 0.94 / 28 dp corners and collapses it on commit.
 */
@Composable
fun NowPlayingSlot(state: NowPlayingSlotState, modifier: Modifier = Modifier, onNavigate: (NavKey) -> Unit = {}) {
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
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = state.expanded,
        modifier = modifier,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.defaultSpatialSpec()) { it } + fadeOut(motion.defaultEffectsSpec()),
    ) {
        val collapse = { state.expanded = false }
        NowPlayingOverlay(
            onCollapse = collapse,
            onOpenAlbum = { collapse(); onNavigate(Album(it.album, it.albumArtist ?: it.artist)) },
            onOpenArtist = { collapse(); onNavigate(Artist(it.artist)) },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val p = state.backProgress
                val s = 1f - BACK_SCALE * p
                scaleX = s
                scaleY = s
                shape = RoundedCornerShape(BackCorner * p)
                clip = p > 0f
            },
        )
    }
}

@Composable
fun rememberNowPlayingSlotState(): NowPlayingSlotState {
    var saved by rememberSaveable { mutableStateOf(false) }
    return androidx.compose.runtime.remember { NowPlayingSlotState(saved) }.also { saved = it.expanded }
}
