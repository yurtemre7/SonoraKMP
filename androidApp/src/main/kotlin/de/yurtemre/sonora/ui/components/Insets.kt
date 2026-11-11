package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shared window-inset rules for the edge-to-edge app. Every inset is consumed exactly once:
 * the shell pads/consumes the bottom chrome (mini player + nav bar), screens consume the top and horizontal sides.
 */
object SonoraInsets {
    /** Status bar + display cutout + horizontal system bars: what a top bar must stay clear of. */
    val top: WindowInsets
        @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

    /** Navigation bar + cutout at the bottom and the sides (landscape 3-button bar). */
    val bottom: WindowInsets
        @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

    /** Horizontal-only safe area, for list bodies under a top bar. */
    val horizontal: WindowInsets
        @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
}

/** Pads for [SonoraInsets.bottom] (sheets, bottom bars). */
@Composable
fun Modifier.bottomSafePadding(): Modifier = windowInsetsPadding(SonoraInsets.bottom)

/** Pads by [padding] and marks it consumed so nested Scaffolds don't apply the same insets again. */
fun Modifier.padAndConsume(padding: PaddingValues): Modifier = padding(padding).consumeWindowInsets(padding)
