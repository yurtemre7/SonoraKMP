package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/** Font scale at which the Spine falls back to a horizontal title (DESIGN §8). */
const val SPINE_FALLBACK_FONT_SCALE = 1.5f

fun spineIsHorizontal(fontScale: Float) = fontScale >= SPINE_FALLBACK_FONT_SCALE

/**
 * The Spine (DESIGN §5): the song title bottom-to-top along the left edge in displayLarge condensed,
 * clipped by the edge rather than shrunk. The rotated glyphs are decorative; the title is exposed once as a heading.
 */
@Composable
fun Spine(
    title: String,
    modifier: Modifier = Modifier,
    /** Lay the title out horizontally (large fonts, or panes too short for a vertical spine). */
    horizontal: Boolean = spineIsHorizontal(LocalDensity.current.fontScale),
) {
    val color = MaterialTheme.colorScheme.onSurface
    if (horizontal) {
        Text(
            title,
            modifier = modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Box(modifier.clipToBounds().clearAndSetSemantics { heading(); contentDescription = title }) {
        Text(
            title,
            modifier = Modifier.rotatedBottomToTop(),
            style = MaterialTheme.typography.displayLarge,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/** Measures the child along the parent's height and places it rotated −90° (reads bottom-to-top). */
private fun Modifier.rotatedBottomToTop(): Modifier = layout { measurable, constraints ->
    val along = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
    val p = measurable.measure(Constraints(maxWidth = along))
    layout(p.height, p.width) {
        p.placeWithLayer((p.height - p.width) / 2, (p.width - p.height) / 2) { rotationZ = -90f }
    }
}
