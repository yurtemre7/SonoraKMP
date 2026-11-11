package de.yurtemre.sonora.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.theme.LocalReduceMotion

private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress, android.graphics.Path()).asComposePath()
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

/**
 * Play/pause button (DESIGN §3): Circle while paused ↔ Cookie9Sided while playing. It reacts on press-down
 * (squish + half-way morph toward the next shape) so the tap feels instant, then springs into the new state.
 * [size] 48–88dp.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PulseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    isCompleted: Boolean = false,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val s = size.coerceIn(48.dp, 88.dp)
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val reduce = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val base = if (isPlaying) 1f else 0f
    val target = if (pressed) 0.5f else base
    val progress = remember { Animatable(base) }
    val scale = remember { Animatable(1f) }
    val fast = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(target, reduce) { if (reduce) progress.snapTo(base) else progress.animateTo(target, fast) }
    LaunchedEffect(pressed, reduce) { if (!reduce) scale.animateTo(if (pressed) 0.9f else 1f, fast) }
    val label = stringResource(
        when {
            isPlaying -> R.string.miniplayer_pause
            isCompleted -> R.string.miniplayer_replay
            else -> R.string.miniplayer_play
        },
    )
    Box(
        modifier
            .size(s)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                // Read in the layer block: animation frames re-outline without recomposing.
                shape = MorphShape(morph, progress.value)
                clip = true
            }
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClickLabel = label,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            when {
                isPlaying -> Icons.Rounded.Pause
                isCompleted -> Icons.Rounded.Replay
                else -> Icons.Rounded.PlayArrow
            },
            contentDescription = label,
            tint = content,
            modifier = Modifier.size(s * 0.45f),
        )
    }
}
