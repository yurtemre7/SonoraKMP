package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.library.DurationFormat
import de.yurtemre.sonora.ui.theme.LocalReduceMotion
import de.yurtemre.sonora.ui.theme.tabular

/** Gap between the active wave and the inactive track; the 4 dp thumb sits centred in it. */
private val ThumbGap = 14.dp
private val ThumbSize = DpSize(4.dp, 36.dp)
private const val HAPTIC_SEGMENTS = 20

/**
 * M3 Expressive seek bar (DESIGN §6.2, P5-24): a real [Slider] with the expressive bar thumb (narrows while
 * pressed/dragged) whose track is the M3 [LinearWavyProgressIndicator] — wavy while playing, flat when paused or
 * dragging — with the expressive thumb gap and an end stop indicator. Drag is local (haptic tick per 5 %),
 * [onSeek] fires once on release; elapsed / −remaining in tabular figures underneath.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavySeekBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val duration = durationMs.coerceAtLeast(0)
    val liveFraction = if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var lastSegment by remember { mutableIntStateOf(-1) }
    val onSeekLatest by rememberUpdatedState(onSeek)
    val durationLatest by rememberUpdatedState(duration)

    val sliderState = remember { SliderState(value = liveFraction) }
    SideEffect { if (dragFraction == null) sliderState.value = liveFraction }
    val onValueChange: (Float) -> Unit = { v ->
        val seg = (v * HAPTIC_SEGMENTS).toInt()
        if (dragFraction != null && seg != lastSegment) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
        lastSegment = seg
        dragFraction = v
        sliderState.value = v
    }
    val onValueChangeFinished: () -> Unit = {
        dragFraction?.let { onSeekLatest((it * durationLatest).toLong()) }
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        // The dragged value stays until the next live tick, so the thumb never jumps back.
    }
    LaunchedEffect(liveFraction) { if (!pressed && !dragged) dragFraction = null }

    val fraction = dragFraction ?: liveFraction
    val shownMs = (fraction * duration).toLong()
    val elapsed = DurationFormat.position(shownMs)
    val total = DurationFormat.position(duration)
    val remaining = DurationFormat.position(duration - shownMs)
    val label = stringResource(R.string.seek_bar_label)
    val state = stringResource(R.string.seek_bar_state, elapsed, total)
    val remainingLabel = stringResource(R.string.seek_bar_remaining, remaining)
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        // Not secondaryContainer: in Sleeve that is Label yellow, reserved for small fills (DESIGN §2).
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    val active = pressed || dragged
    val waving = isPlaying && !active && !reduceMotion
    val density = LocalDensity.current
    val gapPx = with(density) { ThumbGap.roundToPx() }
    val stroke = remember(density) { Stroke(width = with(density) { 5.dp.toPx() }, cap = StrokeCap.Round) }

    Column(modifier.fillMaxWidth()) {
        Slider(
            state = sliderState,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            enabled = duration > 0,
            interactionSource = interaction,
            colors = colors,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    stateDescription = state
                },
            thumb = { s: SliderState ->
                SliderDefaults.Thumb(interactionSource = interaction, colors = colors, enabled = duration > 0, thumbSize = ThumbSize)
            },
            track = { s: SliderState ->
                Box(Modifier.fillMaxWidth().height(ThumbSize.height).clipToBounds(), contentAlignment = Alignment.CenterStart) {
                    LinearWavyProgressIndicator(
                        progress = { s.value },
                        // The indicator puts its gap after the active end; shifting it by half a gap centres
                        // the gap on the thumb for every value.
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .layout { m, c ->
                                val p = m.measure(c)
                                layout(p.width, p.height) { p.place(-gapPx / 2, 0) }
                            },
                        color = colors.activeTrackColor,
                        trackColor = colors.inactiveTrackColor,
                        stroke = stroke,
                        trackStroke = stroke,
                        gapSize = ThumbGap,
                        stopSize = SliderDefaults.TrackStopIndicatorSize,
                        amplitude = { if (waving) 1f else 0f },
                        wavelength = WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
                        waveSpeed = if (reduceMotion) 0.dp else WavyProgressIndicatorDefaults.LinearDeterminateWavelength / 2,
                    )
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val style = MaterialTheme.typography.labelLarge.tabular()
            Text(
                elapsed,
                style = style,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "-$remaining",
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = remainingLabel },
            )
        }
    }
}
