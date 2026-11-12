package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import de.yurtemre.sonora.playback.api.QueueUiState
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.theme.LocalReduceMotion

/** Share of the slot the sleeve takes in the vinyl style; the record peeks out by the rest. */
private const val VINYL_SLEEVE = 0.7f

/**
 * Vinyl style (P5-28): the sleeve slides half out to the left and reveals a flat record — concentric outline
 * grooves and a label disc in the cover's seed colour — that turns while playing. The sleeve keeps the pager
 * gestures of [ArtworkArea]. Reduce motion holds the record still.
 */
@Composable
fun VinylArtwork(
    song: Song,
    isPlaying: Boolean,
    immersive: Boolean,
    onToggleImmersive: () -> Unit,
    modifier: Modifier = Modifier,
    queue: QueueUiState? = null,
    onSkipTo: (Long) -> Unit = {},
) {
    BoxWithConstraints(modifier.aspectRatio(1f, matchHeightConstraintsFirst = true)) {
        val side = minOf(maxWidth, maxHeight) * VINYL_SLEEVE
        Record(
            song.seedColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
            isPlaying,
            Modifier.align(Alignment.CenterStart).offset(x = minOf(maxWidth, maxHeight) - side).size(side),
        )
        ArtworkArea(song, immersive, onToggleImmersive, Modifier.align(Alignment.CenterStart).size(side), queue, onSkipTo)
    }
}

@Composable
private fun Record(label: Color, isPlaying: Boolean, modifier: Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val angle = remember { Animatable(0f) }
    LaunchedEffect(isPlaying, reduceMotion) {
        if (!isPlaying || reduceMotion) return@LaunchedEffect
        while (true) {
            val from = angle.value % 360f
            angle.snapTo(from)
            angle.animateTo(from + 360f, tween(1800, easing = LinearEasing)) // 33⅓ rpm
        }
    }
    val disc = MaterialTheme.colorScheme.inverseSurface
    val groove = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.22f)
    val spindle = MaterialTheme.colorScheme.surface
    Canvas(modifier.clearAndSetSemantics { }.graphicsLayer { rotationZ = angle.value }) {
        val r = size.minDimension / 2
        drawCircle(disc, r)
        val thin = Stroke(width = 1f)
        var gr = r * 0.95f
        while (gr > r * 0.4f) {
            drawCircle(groove, gr, style = thin)
            gr -= r * 0.045f
        }
        drawCircle(label, r * 0.34f)
        // A small flat mark on the label makes the rotation readable.
        drawCircle(spindle.copy(alpha = 0.5f), r * 0.05f, center.copy(x = center.x + r * 0.2f))
        drawCircle(spindle, r * 0.035f)
    }
}
