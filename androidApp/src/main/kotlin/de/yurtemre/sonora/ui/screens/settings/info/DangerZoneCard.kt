package de.yurtemre.sonora.ui.screens.settings.info

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val HOLD_MILLIS = 3_000

/**
 * SET-08 hold-to-reset: pressing fills the card over 3 s; lifting or moving past touch slop drains it.
 * Light haptic on start, heavy on completion. Accessibility services get a plain click action instead
 * (the confirm dialog that follows is the real guard).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DangerZoneCard(busy: Boolean, onHoldComplete: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val shape = MaterialTheme.shapes.large
    val fill = cs.error.copy(alpha = 0.2f)
    val label = stringResource(R.string.settings_reset_title)

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .background(cs.errorContainer)
            .drawBehind { drawRect(fill, size = size.copy(width = size.width * progress.value)) }
            .border(1.dp, cs.error.copy(alpha = 0.5f + 0.5f * progress.value), shape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label) { if (!busy) onHoldComplete(); true }
            }
            .pointerInput(busy) {
                if (busy) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val filling = scope.launch {
                        try {
                            val remaining = ((1f - progress.value) * HOLD_MILLIS).toInt()
                            progress.animateTo(1f, tween(remaining, easing = LinearEasing))
                            haptics.performHapticFeedback(HapticFeedbackType.Reject)
                            onHoldComplete()
                            progress.snapTo(0f)
                        } catch (_: CancellationException) {
                        }
                    }
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || (change.position - down.position).getDistance() > slop) break
                    }
                    if (progress.value < 1f) {
                        filling.cancel()
                        scope.launch { progress.animateTo(0f, tween(250)) }
                    }
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AnimatedContent(busy, label = "dangerIcon") { b ->
                if (b) LoadingIndicator(Modifier.size(24.dp), color = cs.error)
                else Icon(Icons.Rounded.WarningAmber, null, tint = cs.error, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.error)
                Text(
                    stringResource(if (busy) R.string.settings_reset_wiping else R.string.settings_reset_hold),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onErrorContainer,
                )
            }
        }
    }
}
