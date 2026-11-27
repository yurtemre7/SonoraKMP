package de.yurtemre.sonora.ui.screens.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.HomeLayout
import de.yurtemre.sonora.shared.model.HomeSection
import de.yurtemre.sonora.shared.model.HomeSectionState
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsRow
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Home layout: drag the sections of the Home screen into the order you want and hide the ones you do not use. */
@Composable
fun SettingsHomeLayoutRoute(onBack: () -> Unit) {
    val repo: SettingsRepository = koinInject()
    val stored by remember(repo) { repo.settings.map { it.homeLayout } }.collectAsStateWithLifecycle(HomeLayout.default)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // A local copy so a drag is smooth; it is saved when the drag ends and follows the stored value otherwise.
    var items by remember { mutableStateOf(stored) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(stored) { if (!dragging) items = stored }
    fun save(next: List<HomeSectionState>) {
        items = next
        scope.launch { repo.setHomeLayout(next) }
    }

    val listState = rememberLazyListState()
    // Row 0 is the explainer, so list indexes are shifted by one.
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        dragging = true
        items = items.toMutableList().apply { add(to.index - 1, removeAt(from.index - 1)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && dragging) {
            dragging = false
            repo.setHomeLayout(items)
        }
    }

    val handleLabel = stringResource(R.string.home_layout_drag_handle)
    val upLabel = stringResource(R.string.home_layout_move_up)
    val downLabel = stringResource(R.string.home_layout_move_down)

    SettingsScaffold(stringResource(R.string.home_layout_title), onBack, listState = listState) {
        item(key = "explainer") { SettingsNote(stringResource(R.string.home_layout_explainer)) }
        itemsIndexed(items, key = { _, it -> it.section.key }) { index, state ->
            ReorderableItem(reorderState, key = state.section.key) { isDragging ->
                SettingsRow(
                    title = stringResource(state.section.labelRes),
                    modifier = Modifier
                        .background(
                            if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.surface,
                        )
                        .toggleable(value = state.visible, role = Role.Switch) { on ->
                            haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            save(items.toMutableList().apply { set(index, state.copy(visible = on)) })
                        }
                        .semantics {
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction(upLabel) {
                                    save(items.toMutableList().apply { add(index - 1, removeAt(index)) }); true
                                })
                                if (index < items.lastIndex) add(CustomAccessibilityAction(downLabel) {
                                    save(items.toMutableList().apply { add(index + 1, removeAt(index)) }); true
                                })
                            }
                        },
                    trailing = {
                        Switch(checked = state.visible, onCheckedChange = null)
                        IconButton(onClick = {}, modifier = Modifier.draggableHandle().size(48.dp)) {
                            Icon(Icons.Filled.DragHandle, contentDescription = handleLabel)
                        }
                    },
                )
            }
        }
        item(key = "reset") {
            TextButton(onClick = { save(HomeLayout.default) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.home_layout_reset))
            }
        }
    }
}

private val HomeSection.labelRes: Int get() = when (this) {
    HomeSection.JumpBack -> R.string.home_jump_back_in
    HomeSection.Actions -> R.string.home_layout_actions
    HomeSection.Glance -> R.string.home_layout_glance
    HomeSection.Favorite -> R.string.home_layout_favorite
    HomeSection.Tiles -> R.string.home_layout_tiles
    HomeSection.Favorites -> R.string.home_action_favorites
    HomeSection.Recommended -> R.string.home_recommended
    HomeSection.TimeMix -> R.string.home_layout_time_mix
    HomeSection.Weekly -> R.string.home_layout_weekly
    HomeSection.Recent -> R.string.home_recent
}
