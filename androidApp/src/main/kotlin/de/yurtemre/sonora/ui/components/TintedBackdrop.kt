package de.yurtemre.sonora.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Fraction the backdrop is tinted toward the cover's dominant colour (DESIGN §6.3). */
const val BACKDROP_TINT = 0.12f

/** Flat Now Playing backdrop (DESIGN §6.3): surfaceContainerLow tinted 12% toward [dominant]. No blur, no gradient. */
@Composable
fun TintedBackdrop(dominant: Color?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val target = if (dominant == null) base else lerp(base, dominant, BACKDROP_TINT)
    val color by animateColorAsState(target, MaterialTheme.motionScheme.slowEffectsSpec(), label = "backdrop")
    Box(modifier.background(color), content = content)
}
