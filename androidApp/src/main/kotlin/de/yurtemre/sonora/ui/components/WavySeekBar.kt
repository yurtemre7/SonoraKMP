package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.library.DurationFormat
import de.yurtemre.sonora.ui.theme.LocalReduceMotion
import de.yurtemre.sonora.ui.theme.tabular

/**
 * Seek bar (DESIGN §6.2): wavy track (flat when paused), draggable thumb, elapsed / remaining in
 * tabular figures. Dragging is local until release, then [onSeek] fires once.
 */
@Composable
fun WavySeekBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val duration = durationMs.coerceAtLeast(0)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val liveFraction = if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val fraction = dragFraction ?: liveFraction
    val shownMs = (fraction * duration).toLong()
    val elapsed = DurationFormat.position(shownMs)
    val total = DurationFormat.position(duration)
    val remaining = DurationFormat.position(duration - shownMs)
    val label = stringResource(R.string.seek_bar_label)
    val state = stringResource(R.string.seek_bar_state, elapsed, total)
    val remainingLabel = stringResource(R.string.seek_bar_remaining, remaining)
    val color = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    val interaction = remember { MutableInteractionSource() }
    val sliderState = remember { SliderState() }
    SideEffect { if (dragFraction == null) sliderState.value = liveFraction }
    Column(modifier.fillMaxWidth()) {
        Slider(
            state = sliderState,
            onValueChange = {
                dragFraction = it
                sliderState.value = it
            },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * duration).toLong()) }
                dragFraction = null
            },
            enabled = duration > 0,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = state
                },
            thumb = { _: SliderState ->
                Box(Modifier.size(16.dp).clip(CircleShape).background(color))
            },
            track = { sliderState: SliderState ->
                LinearWavyProgressIndicator(
                    progress = { sliderState.value },
                    modifier = Modifier.fillMaxWidth(),
                    color = color,
                    trackColor = trackColor,
                    amplitude = { _ -> if (isPlaying && dragFraction == null) 1f else 0f },
                    waveSpeed = if (reduceMotion) 0.dp else WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
                )
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val style = MaterialTheme.typography.labelMedium.tabular()
            Text(elapsed, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                "-$remaining",
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = remainingLabel
                },
            )
        }
    }
}
