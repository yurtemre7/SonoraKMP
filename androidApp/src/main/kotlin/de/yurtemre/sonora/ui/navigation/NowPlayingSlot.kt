package de.yurtemre.sonora.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException

/** Now Playing overlay state owned by the shell (SPEC §6). Collapsed by default. */
@Stable
class NowPlayingSlotState(initialExpanded: Boolean = false) {
    var expanded by mutableStateOf(initialExpanded)
    /** Predictive back progress 0..1 driving the collapse (DESIGN §4). */
    var backProgress by mutableFloatStateOf(0f)
}

/**
 * Placeholder overlay slot; P5-07 replaces the content with `NowPlayingOverlay`.
 * Back rule 4: when expanded, predictive back collapses it.
 */
@Composable
fun NowPlayingSlot(state: NowPlayingSlotState, modifier: Modifier = Modifier) {
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
    if (state.expanded) {
        Box(modifier.fillMaxSize())
    }
}

@Composable
fun rememberNowPlayingSlotState(): NowPlayingSlotState {
    var saved by rememberSaveable { mutableStateOf(false) }
    return androidx.compose.runtime.remember { NowPlayingSlotState(saved) }.also { saved = it.expanded }
}
