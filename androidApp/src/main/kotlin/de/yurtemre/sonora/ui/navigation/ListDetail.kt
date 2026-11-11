package de.yurtemre.sonora.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Text-heavy screens (settings, changelog, licenses) stay readable on wide windows: max 720 dp, centred (DESIGN §7). */
@Composable
fun TextWidth(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp).fillMaxWidth()) { content() }
    }
}

/**
 * Expanded-width list-detail (UI-34): the library list on the left and, when present, a detail (pushed destination
 * or Now Playing) on the right. A draggable handle splits the panes; with Now Playing the Spine is the divider, so no
 * handle is drawn. Without a detail the list takes the whole width.
 */
@Composable
internal fun ListDetail(
    list: @Composable (Modifier) -> Unit,
    detail: (@Composable (Modifier) -> Unit)?,
    spineDivider: Boolean,
) {
    var split by rememberSaveable { mutableFloatStateOf(0.42f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val totalPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
        if (detail == null) { list(Modifier.fillMaxSize()); return@BoxWithConstraints }
        Row(Modifier.fillMaxSize()) {
            list(Modifier.weight(split).fillMaxHeight())
            if (!spineDivider) {
                Box(
                    Modifier.width(16.dp).fillMaxHeight().draggable(
                        rememberDraggableState { split = (split + it / totalPx).coerceIn(0.28f, 0.65f) },
                        Orientation.Horizontal,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(4.dp, 48.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
                }
            }
            detail(Modifier.weight(1f - split).fillMaxHeight())
        }
    }
}
