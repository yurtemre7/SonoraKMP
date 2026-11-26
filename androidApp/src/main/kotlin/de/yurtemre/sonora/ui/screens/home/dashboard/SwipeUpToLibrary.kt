package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * Dragging up past the end of the dashboard by [thresholdPx] (cumulative, direct touch only) opens the Library,
 * once per gesture. Fling sources are ignored; the connection re-arms when the gesture's fling callbacks run.
 */
internal class SwipeUpToLibrary(private val thresholdPx: Float, private val onTrigger: () -> Unit) : NestedScrollConnection {
    private var pulled = 0f

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y < 0f && consumed.y == 0f) {
            if (pulled.isFinite()) {
                pulled += -available.y
                if (pulled > thresholdPx) { pulled = Float.NEGATIVE_INFINITY; onTrigger() }
            }
        } else if (source == NestedScrollSource.UserInput && (available.y > 0f || consumed.y != 0f)) {
            if (pulled.isFinite()) pulled = 0f
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity { pulled = 0f; return Velocity.Zero }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity { pulled = 0f; return Velocity.Zero }
}
