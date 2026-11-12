package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.playback.api.PlayerUiState
import de.yurtemre.sonora.playback.api.QueueUiState
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.format.DurationFormat
import de.yurtemre.sonora.ui.theme.tabular

/**
 * Minimalist style (P5-29): no artwork. The Spine takes the full height beside large tabular time, the artist
 * line and the controls. A horizontal swipe anywhere on the pane skips (left = next, right = previous); the
 * overlay's drag-down still collapses. Works the same in landscape since it is already a row.
 */
@Composable
internal fun MinimalPlayer(
    song: Song,
    state: PlayerUiState,
    isFavorite: Boolean,
    immersive: Boolean,
    onViewChange: (NowPlayingView) -> Unit,
    actions: PlayerActions,
    seekBar: @Composable () -> Unit,
    elapsed: @Composable () -> Unit,
    visualizer: (@Composable () -> Unit)?,
    @Suppress("UNUSED_PARAMETER") queue: QueueUiState?,
    @Suppress("UNUSED_PARAMETER") onSkipTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val latest by rememberUpdatedState(actions)
    val swipe = Modifier.pointerInput(threshold) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (kotlin.math.abs(total) > threshold) {
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    if (total < 0) latest.onNext() else latest.onPrevious()
                }
            },
        ) { _, dx -> total += dx }
    }
    val horizontalSpine = spineIsHorizontal(LocalDensity.current.fontScale)
    Row(modifier.fillMaxSize().padding(horizontal = 16.dp).then(swipe)) {
        if (!horizontalSpine) {
            Spine(song.title, Modifier.fillMaxHeight().widthIn(max = 96.dp).padding(bottom = 16.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (horizontalSpine) Spine(song.title)
            elapsed()
            Text(
                song.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(24.dp))
            visualizer?.invoke()
            seekBar()
            Transport(state, actions)
            if (!immersive) {
                SecondaryRow(state, isFavorite, actions)
                ViewSwitcher(NowPlayingView.Player, onViewChange)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Large tabular "elapsed / total" for the minimalist style. */
@Composable
internal fun MinimalTime(positionMs: Long, durationMs: Long) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
        Text(
            DurationFormat.clockMs(positionMs),
            style = MaterialTheme.typography.displayLarge.tabular(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            " / " + DurationFormat.clockMs(durationMs),
            style = MaterialTheme.typography.titleLarge.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}
