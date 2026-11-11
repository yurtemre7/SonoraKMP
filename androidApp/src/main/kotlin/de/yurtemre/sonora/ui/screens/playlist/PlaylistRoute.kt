package de.yurtemre.sonora.ui.screens.playlist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.R
import de.yurtemre.sonora.platform.ShareHelper
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.ArtSize
import de.yurtemre.sonora.ui.components.ConfirmDialog
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.MultiSelectActionBar
import de.yurtemre.sonora.ui.components.SongRow
import de.yurtemre.sonora.ui.components.SongRowAction
import de.yurtemre.sonora.ui.components.rememberSelectionState
import de.yurtemre.sonora.ui.format.rememberTotalDurationText
import de.yurtemre.sonora.ui.sheets.EditDescriptionDialog
import de.yurtemre.sonora.ui.sheets.PlaylistSelectorSheetHost
import de.yurtemre.sonora.ui.sheets.RenamePlaylistDialog
import de.yurtemre.sonora.ui.sheets.SongActionsHost
import de.yurtemre.sonora.ui.sheets.SongSheetAction
import de.yurtemre.sonora.ui.sheets.rememberSongActionsState
import de.yurtemre.sonora.ui.theme.SleeveShape
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class Dialog { None, Rename, Description, Delete, AddSongs, AddToPlaylist }

/** Items above the songs in the list: header, play group, add-songs row. */
private const val HEADER_ITEMS = 3

/**
 * Playlist detail (UI-25). [onNavigate] opens album/artist from the song menu; when the shell does not
 * pass it those entries are hidden.
 */
@Composable
fun PlaylistRoute(id: String, onBack: () -> Unit, onNavigate: ((NavKey) -> Unit)? = null) {
    val playlists: PlaylistRepository = koinInject()
    val library: LibraryRepository = koinInject()
    val favorites: FavoritesRepository = koinInject()
    val vm = viewModel(key = "playlist_$id") { PlaylistDetailViewModel(id, playlists, library, favorites) }
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        PlaylistDetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        PlaylistDetailState.NotFound -> NotFound(onBack)
        is PlaylistDetailState.Loaded -> PlaylistDetailScreen(s, vm, onBack, onNavigate)
    }
}

@Composable
private fun NotFound(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = {}, navigationIcon = { BackButton(onBack) }) }) { pad ->
        EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.playlist_detail_not_found), Modifier.padding(pad))
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.playlist_detail_back)) }
}

@Composable
private fun PlaylistDetailScreen(
    s: PlaylistDetailState.Loaded,
    vm: PlaylistDetailViewModel,
    onBack: () -> Unit,
    onNavigate: ((NavKey) -> Unit)?,
) {
    val context = LocalContext.current
    val contextRes = LocalResources.current
    val res = context.resources
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val playback: PlaybackConnection = koinInject()
    val snackbar = remember { SnackbarHostState() }
    val selection = rememberSelectionState()
    val actions = rememberSongActionsState()
    var dialog by rememberSaveable { mutableStateOf(Dialog.None) }
    var multiTargets by remember { mutableStateOf<List<Long>>(emptyList()) }
    val playlist = s.playlist

    // Local copy for immediate drag/swipe feedback; resynced whenever the repository emits.
    var entries by remember { mutableStateOf(s.entries) }
    LaunchedEffect(s.entries) { entries = s.entries }
    val songs = entries.map { it.song }

    val undoLabel = stringResource(R.string.playlist_detail_undo)
    fun snack(msg: String, onUndo: (() -> Unit)? = null) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        val r = snackbar.showSnackbar(msg, if (onUndo != null) undoLabel else null, duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) onUndo?.invoke()
    }
    fun removeIds(ids: List<Long>, msg: String) {
        val previous = playlist.songIds
        entries = entries.filterNot { it.song.id in ids }
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        vm.remove(ids)
        snack(msg) { vm.restore(previous, ids) }
    }
    fun moveTo(from: Int, to: Int) {
        val next = entries.toMutableList().apply { add(to, removeAt(from)) }
        entries = next
        vm.reorder(next.map { it.song.id })
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = CoverImage.toJpeg(context, uri)
            if (bytes == null) snack(res.getString(R.string.playlist_detail_cover_failed)) else vm.setCover(bytes)
        }
    }
    val pickCover = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val f = from.index - HEADER_ITEMS
        val t = to.index - HEADER_ITEMS
        if (f in entries.indices && t in entries.indices) {
            entries = entries.toMutableList().apply { add(t, removeAt(f)) }
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    // Persist once the drag settles.
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging) {
            val order = entries.map { it.song.id }
            if (order.size == s.entries.size && order != s.entries.map { it.song.id }) vm.reorder(order)
        }
    }
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(showTitle) {
                        Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (selection.active) {
                        IconButton(onClick = {
                            val ids = selection.selected.toList()
                            selection.clear()
                            removeIds(ids, res.getQuantityString(R.plurals.playlist_detail_removed_many, ids.size, ids.size))
                        }) { Icon(Icons.Rounded.PlaylistRemove, stringResource(R.string.playlist_detail_remove)) }
                    } else {
                        Overflow(
                            hasCover = playlist.coverPath != null,
                            onRename = { dialog = Dialog.Rename },
                            onChangeCover = pickCover,
                            onRemoveCover = { vm.setCover(null) },
                            onExport = {
                                scope.launch {
                                    val path = vm.exportM3u()
                                    val ok = path != null &&
                                        ShareHelper.share(context, listOf(ShareHelper.Item(path, mimeType = "audio/x-mpegurl")))
                                    if (!ok) snack(res.getString(R.string.playlist_detail_export_failed))
                                }
                            },
                            onDelete = { dialog = Dialog.Delete },
                        )
                    }
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(selection.active) {
                fun pick(ids: Set<Long>) = songs.filter { it.id in ids }.distinctBy { it.id }
                MultiSelectActionBar(
                    selection = selection,
                    allIds = { songs.map { it.id } },
                    snackbarHostState = snackbar,
                    onPlayNext = { ids -> playback.playNext(pick(ids)) },
                    onAddToQueue = { ids -> playback.addToQueue(pick(ids)) },
                    onAddToPlaylist = { ids -> multiTargets = ids.toList(); dialog = Dialog.AddToPlaylist },
                    allFavorite = selection.active && selection.selected.all { it in s.favoriteIds },
                    onFavoriteToggle = { ids, fav -> vm.setFavorite(pick(ids), fav) },
                    shareItems = { ids -> pick(ids).map { ShareHelper.Item(it.filePath, it.id) } },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val removeLabel = stringResource(R.string.playlist_detail_remove)
        val upLabel = stringResource(R.string.playlist_detail_move_up)
        val downLabel = stringResource(R.string.playlist_detail_move_down)
        val handleLabel = stringResource(R.string.playlist_detail_drag_handle)
        val player by playback.player.collectAsStateWithLifecycle()
        val currentId = player.currentSong?.id
        fun removedMsg(song: Song) = res.getString(R.string.playlist_detail_removed, song.title)

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Header(
                    name = playlist.name,
                    description = playlist.description,
                    cover = playlist.coverPath,
                    count = entries.size,
                    totalMs = entries.sumOf { it.song.durationMs },
                    onEditDescription = { dialog = Dialog.Description },
                    onCoverClick = pickCover,
                )
            }
            item(key = "play") {
                PlayGroup(
                    enabled = songs.isNotEmpty(),
                    onPlay = { songs.firstOrNull()?.let { playback.playSong(it, songs, playlist.id) } },
                    onShuffle = { playback.playShuffled(songs, playlist.id) },
                )
            }
            item(key = "add") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(role = Role.Button) { dialog = Dialog.AddSongs }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, SleeveShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                    Text(stringResource(R.string.playlist_detail_add_songs), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (entries.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        stringResource(R.string.playlist_detail_empty_title),
                        subtitle = stringResource(R.string.playlist_detail_empty_subtitle),
                    )
                }
            }
            itemsIndexed(entries, key = { _, e -> e.key }) { index, entry ->
                val song = entry.song
                ReorderableItem(reorderState, key = entry.key) { dragging ->
                    val dismiss = rememberSwipeToDismissBoxState()
                    SwipeToDismissBox(
                        state = dismiss,
                        enableDismissFromStartToEnd = false,
                        gesturesEnabled = !selection.active,
                        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) removeIds(listOf(song.id), removedMsg(song)) },
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer) }
                        },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().background(
                                if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SongRow(
                                title = song.title,
                                artist = song.artist,
                                durationMs = song.durationMs,
                                artwork = song.artworkPath,
                                showArtwork = true,
                                isPlaying = song.id == currentId,
                                selected = selection.isSelected(song.id),
                                onClick = { if (!selection.onTap(song.id)) playback.playSong(song, songs, playlist.id) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selection.onLongPress(song.id)
                                },
                                onMoreClick = if (selection.active) null else ({ actions.show(song) }),
                                accessibilityActions = buildList {
                                    add(SongRowAction(removeLabel) { removeIds(listOf(song.id), removedMsg(song)) })
                                    if (index > 0) add(SongRowAction(upLabel) { moveTo(index, index - 1) })
                                    if (index < entries.lastIndex) add(SongRowAction(downLabel) { moveTo(index, index + 1) })
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (!selection.active) {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(
                                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                        onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                                    ).size(48.dp),
                                ) { Icon(Icons.Rounded.DragHandle, handleLabel) }
                            }
                        }
                    }
                }
            }
        }
    }

    val removeFromPlaylist = stringResource(R.string.playlist_detail_remove)
    SongActionsHost(
        state = actions,
        snackbarHostState = snackbar,
        onGoToAlbum = onNavigate?.let { nav -> { song: Song -> nav(de.yurtemre.sonora.ui.navigation.albumKeyOf(song)) } },
        onGoToArtist = onNavigate?.let { nav -> { song: Song -> nav(de.yurtemre.sonora.ui.navigation.artistKeyOf(song)) } },
        extraActions = { song ->
            listOf(
                SongSheetAction(removeFromPlaylist, Icons.Rounded.PlaylistRemove, destructive = true) {
                    removeIds(listOf(song.id), res.getString(R.string.playlist_detail_removed, song.title))
                },
            )
        },
    )

    when (dialog) {
        Dialog.Rename -> RenamePlaylistDialog(playlist.name, vm::rename) { dialog = Dialog.None }
        Dialog.Description -> EditDescriptionDialog(playlist.description, vm::setDescription) { dialog = Dialog.None }
        Dialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.playlist_detail_delete_title),
            message = stringResource(R.string.playlist_detail_delete_message, playlist.name),
            confirmLabel = stringResource(R.string.playlist_detail_delete_confirm),
            destructive = true,
            onConfirm = { onBack(); vm.delete() },
            onDismiss = { dialog = Dialog.None },
        )
        Dialog.AddSongs -> AddSongsSheet(
            existing = playlist.songIds.toSet(),
            onAdd = { ids ->
                vm.addSongs(ids)
                snack(res.getQuantityString(R.plurals.playlist_added, ids.size, ids.size))
            },
            onDismiss = { dialog = Dialog.None },
        )
        Dialog.AddToPlaylist -> PlaylistSelectorSheetHost(
            songIds = multiTargets,
            onDismiss = { dialog = Dialog.None; selection.clear() },
            onMessage = { snack(it) },
        )
        Dialog.None -> Unit
    }
}

@Composable
private fun Header(
    name: String,
    description: String?,
    cover: String?,
    count: Int,
    totalMs: Long,
    onEditDescription: () -> Unit,
    onCoverClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val coverDesc = stringResource(R.string.playlist_detail_cover_desc, name)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlbumArt(
            cover, name,
            Modifier
                .widthIn(max = 240.dp)
                .fillMaxWidth(0.62f)
                .aspectRatio(1f)
                .clickable(onClickLabel = stringResource(R.string.playlist_detail_change_cover), onClick = onCoverClick)
                .semantics { contentDescription = coverDesc },
            size = ArtSize.Medium,
            shape = SleeveShape,
            decorative = true,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onEditDescription)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                description ?: stringResource(R.string.playlist_detail_no_description),
                style = MaterialTheme.typography.bodyMedium,
                color = if (description == null) cs.primary else cs.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
        }
        Text(
            stringResource(
                R.string.playlist_detail_count_time,
                pluralStringResource(R.plurals.song_count, count, count),
                rememberTotalDurationText(totalMs / 1000),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlayGroup(enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        Button(
            onClick = onPlay, enabled = enabled, shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        ) {
            Icon(Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.playlist_detail_play))
        }
        FilledTonalButton(
            onClick = onShuffle, enabled = enabled, shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        ) {
            Icon(Icons.Rounded.Shuffle, null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.playlist_detail_shuffle))
        }
    }
}

@Composable
private fun Overflow(
    hasCover: Boolean,
    onRename: () -> Unit,
    onChangeCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.playlist_detail_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuEntry(R.string.playlist_detail_rename, Icons.Rounded.Edit) { open = false; onRename() }
            MenuEntry(R.string.playlist_detail_change_cover, Icons.Rounded.Image) { open = false; onChangeCover() }
            if (hasCover) MenuEntry(R.string.playlist_detail_remove_cover, Icons.Rounded.HideImage) { open = false; onRemoveCover() }
            MenuEntry(R.string.playlist_detail_export, Icons.Rounded.FileUpload) { open = false; onExport() }
            MenuEntry(R.string.playlist_detail_delete, Icons.Rounded.Delete) { open = false; onDelete() }
        }
    }
}

@Composable
private fun MenuEntry(label: Int, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}
