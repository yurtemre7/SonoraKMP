package de.yurtemre.sonora.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** Shared expand/collapse handle for the Now Playing overlay; backed by the shell's [NowPlayingSlotState]. */
@Stable
class NowPlayingController(internal val slot: NowPlayingSlotState) {
    val isExpanded: Boolean get() = slot.expanded
    fun expand() { slot.expanded = true }
    fun collapse() { slot.expanded = false }
}

val LocalNowPlayingController = staticCompositionLocalOf<NowPlayingController> {
    error("NowPlayingController not provided")
}

@Composable
fun rememberNowPlayingController(slot: NowPlayingSlotState): NowPlayingController = remember(slot) { NowPlayingController(slot) }
