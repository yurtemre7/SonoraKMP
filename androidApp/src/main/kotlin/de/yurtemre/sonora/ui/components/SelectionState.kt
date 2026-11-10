package de.yurtemre.sonora.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Multi-select state (UI-13): ids of selected songs. Enter via [onLongPress], exit via back or [clear]. */
@Stable
class SelectionState(initial: Set<Long> = emptySet()) {
    var selected: Set<Long> by mutableStateOf(initial)
        private set

    val active: Boolean get() = selected.isNotEmpty()
    val count: Int get() = selected.size

    fun isSelected(id: Long): Boolean = id in selected

    /** Long-press: enters selection mode with [id] (or toggles it when already active). */
    fun onLongPress(id: Long) = toggle(id)

    /** Tap handler: returns true if consumed (selection mode active), else caller should open the item. */
    fun onTap(id: Long): Boolean {
        if (!active) return false
        toggle(id)
        return true
    }

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun selectAll(ids: Collection<Long>) {
        selected = ids.toSet()
    }

    fun clear() {
        selected = emptySet()
    }

    companion object {
        val Saver: Saver<SelectionState, LongArray> = Saver(
            save = { it.selected.toLongArray() },
            restore = { SelectionState(it.toSet()) },
        )
    }
}

/** Remembers a [SelectionState] across config changes and clears it on system back while active. */
@Composable
fun rememberSelectionState(): SelectionState {
    val state = rememberSaveable(saver = SelectionState.Saver) { SelectionState() }
    BackHandler(enabled = state.active) { state.clear() }
    return state
}
