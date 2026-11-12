package de.yurtemre.sonora.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.ui.theme.LocalReduceMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Decorative level bars (P5-30). Like the Flutter app this is driven by [isPlaying] only — no FFT, so no
 * RECORD_AUDIO permission. Thin flat Cobalt bars; they freeze where they are when paused and hold a static
 * low profile under reduce motion.
 */
@Composable
fun Visualizer(isPlaying: Boolean, modifier: Modifier = Modifier, bars: Int = 32, height: Dp = 28.dp) {
    val reduceMotion = LocalReduceMotion.current
    val levels = remember(bars) { List(bars) { Animatable(0.15f + 0.1f * ((it * 7) % 5) / 4f) } }
    LaunchedEffect(isPlaying, reduceMotion, levels) {
        if (!isPlaying || reduceMotion) return@LaunchedEffect
        coroutineScope {
            levels.forEachIndexed { i, a ->
                launch {
                    val rnd = Random(i * 31 + 7)
                    while (true) {
                        val ms = 140 + rnd.nextInt(220)
                        a.animateTo(0.12f + rnd.nextFloat() * 0.88f, tween(ms))
                    }
                }
            }
            delay(Long.MAX_VALUE)
        }
    }
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
        val stroke = 3.dp.toPx()
        val step = size.width / bars
        levels.forEachIndexed { i, a ->
            val x = step * i + step / 2
            val h = (size.height * a.value).coerceAtLeast(stroke)
            drawLine(color, Offset(x, size.height), Offset(x, size.height - h), stroke, StrokeCap.Butt)
        }
    }
}
