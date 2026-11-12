package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.compose.foundation.background
import de.yurtemre.sonora.ui.components.displayTitle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.playback.api.QueueItemUi
import de.yurtemre.sonora.ui.components.SongRow
import de.yurtemre.sonora.ui.components.SongRowAction
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Up Next queue (UI-18, PLAY-06): current item highlighted, drag handle to reorder, swipe to remove
 * (by entry uid, with undo snackbar that re-inserts at the old index), tap to skip, scroll-to-current.
 */
@Composable
fun UpNextView(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    connection: PlaybackConnection = koinInject(),
) {
    val queue by connection.queue.collectAsState()
    val player by connection.player.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Local copy so drag feedback is immediate; resynced on every upstream change.
    var items by remember { mutableStateOf(queue.items) }
    LaunchedEffect(queue.items) { items = queue.items }
    val currentUid = queue.items.getOrNull(queue.currentIndex)?.entryUid ?: player.currentEntryUid

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
        connection.moveQueueItem(from.index, to.index)
    }

    val removedText = stringResource(R.string.up_next_removed, "%s")
    val undoLabel = stringResource(R.string.up_next_undo)
    fun remove(item: QueueItemUi, index: Int) {
        items = items.filterNot { it.entryUid == item.entryUid }
        connection.removeFromQueue(item.entryUid)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = removedText.replace("%s", item.song.title),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                connection.insertQueueItem(item.song.id, index)
            }
        }
    }

    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.up_next_empty),
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val removeLabel = stringResource(R.string.up_next_remove)
    val upLabel = stringResource(R.string.up_next_move_up)
    val downLabel = stringResource(R.string.up_next_move_down)
    val handleLabel = stringResource(R.string.up_next_drag_handle)

    Box(modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { _, it -> it.entryUid }) { index, item ->
                ReorderableItem(reorderState, key = item.entryUid) { _ ->
                    val dismissState = rememberSwipeToDismissBoxState()
                    SwipeToDismissBox(
                        state = dismissState,
                        onDismiss = { remove(item, index) },
                        backgroundContent = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .padding(horizontal = 24.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val isCurrent = item.entryUid == currentUid
                            SongRow(
                                title = item.song.displayTitle(),
                                artist = item.song.artist,
                                durationMs = item.song.durationMs,
                                onClick = { connection.skipToQueueItem(item.entryUid) },
                                modifier = Modifier.weight(1f),
                                artwork = item.song.artworkPath,
                                showArtwork = true,
                                isPlaying = isCurrent,
                                selected = isCurrent,
                                accessibilityActions = buildList {
                                    add(SongRowAction(removeLabel) { remove(item, index) })
                                    if (index > 0) add(SongRowAction(upLabel) {
                                        connection.moveQueueItem(index, index - 1)
                                    })
                                    if (index < items.lastIndex) add(SongRowAction(downLabel) {
                                        connection.moveQueueItem(index, index + 1)
                                    })
                                },
                            )
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle().size(48.dp),
                            ) {
                                Icon(Icons.Filled.DragHandle, contentDescription = handleLabel)
                            }
                        }
                    }
                }
            }
        }
        val currentIndex = items.indexOfFirst { it.entryUid == currentUid }
        if (currentIndex >= 0) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(currentIndex) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(
                    Icons.Filled.MyLocation,
                    contentDescription = stringResource(R.string.up_next_scroll_to_current),
                )
            }
        }
    }
}
