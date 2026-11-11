package de.yurtemre.sonora.ui.screens.home.tabs

import de.yurtemre.sonora.ui.sheets.CreatePlaylistDialog
import de.yurtemre.sonora.ui.sheets.EditDescriptionDialog
import de.yurtemre.sonora.ui.sheets.RenamePlaylistDialog
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.library.ScrollSection
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.ConfirmDialog
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.FastScroller
import de.yurtemre.sonora.ui.components.binLabel
import de.yurtemre.sonora.ui.components.binStarts
import de.yurtemre.sonora.ui.components.binsOf
import de.yurtemre.sonora.ui.components.countBand
import de.yurtemre.sonora.ui.components.letterBin
import de.yurtemre.sonora.shared.model.PlaylistSort
import de.yurtemre.sonora.ui.components.SectionHeader
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.screens.home.HomeTabReselect
import de.yurtemre.sonora.ui.screens.home.HomeTabState
import de.yurtemre.sonora.ui.theme.SleeveRowShape
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

private sealed interface PlaylistDialog {
    data object Create : PlaylistDialog
    data class Rename(val p: Playlist) : PlaylistDialog
    data class Description(val p: Playlist) : PlaylistDialog
    data class Delete(val p: Playlist) : PlaylistDialog
}

/** Playlists tab (UI-11): create, M3U import, rows with cover and menu (export, description, cover, rename, delete). */
@Composable
fun PlaylistsTab(state: HomeTabState, snackbar: SnackbarHostState, onOpen: (Playlist) -> Unit) {
    val repo = koinInject<PlaylistRepository>()
    val library = koinInject<LibraryRepository>()
    val songs by library.songs.collectAsStateWithLifecycle(emptyList())
    val artById = remember(songs) { songs.associate { it.id to it.artworkPath } }
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    // Optimistic delete: hidden at once, before the repository flow catches up.
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    var coverTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val playlists = remember(state.playlists, hidden) { state.playlists.filterNot { it.id in hidden } }
    fun snack(text: String, duration: SnackbarDuration = SnackbarDuration.Short) =
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text, duration = duration) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(context, uri) ?: return@rememberLauncherForActivityResult
        if (!name.lowercase().let { it.endsWith(".m3u") || it.endsWith(".m3u8") }) {
            snack(res.getString(R.string.library_import_wrong_type)); return@rememberLauncherForActivityResult
        }
        scope.launch {
            val created = runCatching { repo.importM3u(uri.toString(), name, res.getString(R.string.library_imported_from_m3u)) }.getOrNull()
            snack(res.getString(if (created != null) R.string.library_imported else R.string.library_import_no_matches))
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = coverTarget.also { coverTarget = null }
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = squareCoverJpeg(context, uri)
            if (bytes == null) snack(res.getString(R.string.library_cover_failed)) else repo.setCover(id, bytes)
        }
    }
    val export: (Playlist) -> Unit = { p ->
        scope.launch {
            val ok = runCatching {
                val file = File(repo.exportM3u(p.id))
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("audio/x-mpegurl")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_TEXT, res.getString(R.string.library_exported, p.name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, res.getString(R.string.platform_share_chooser_title)))
            }.isSuccess
            if (!ok) snack(res.getString(R.string.library_export_failed))
        }
    }
    val importM3u = { importer.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "*/*")) }

    LaunchedEffect(listState) {
        HomeTabReselect.events.collect { if (it == LibraryTab.Playlists) listState.animateScrollToItem(0) }
    }

    when {
        !state.loaded -> Unit
        state.totalCount == 0 -> EmptyState(
            icon = Icons.Rounded.QueueMusic,
            title = stringResource(R.string.library_playlists_empty),
            subtitle = stringResource(R.string.library_playlists_empty_subtitle),
            modifier = Modifier.fillMaxSize(),
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { dialog = PlaylistDialog.Create }) { Text(stringResource(R.string.library_new_playlist)) }
                    TextButton(onClick = importM3u) { Text(stringResource(R.string.library_import_m3u)) }
                }
            },
        )
        playlists.isEmpty() -> EmptyState(
            icon = Icons.Rounded.SearchOff,
            title = stringResource(R.string.library_playlists_no_match),
            subtitle = stringResource(R.string.library_no_match_subtitle, state.query.trim()),
            modifier = Modifier.fillMaxSize(),
        )
        else -> Box(Modifier.fillMaxSize()) {
            val sort = PlaylistSort.fromStorage(state.sort.key)
            val bins = remember(playlists, sort, state.librarySongIds) {
                binsOf(playlists) {
                    if (sort == PlaylistSort.Songs) countBand(it.songIds.count { id -> id in state.librarySongIds }) else letterBin(it.name)
                }
            }
            val labels = bins.map { binLabel(it.key) }
            val starts = remember(bins) { binStarts(bins, leading = 1) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item(key = "header") {
                    SectionHeader(
                        text = stringResource(R.string.library_playlists_header),
                        count = playlists.size,
                        trailing = {
                            Row {
                                IconButton(onClick = importM3u) { Icon(Icons.Rounded.FileDownload, stringResource(R.string.library_import_m3u)) }
                                IconButton(onClick = { dialog = PlaylistDialog.Create }) { Icon(Icons.Rounded.Add, stringResource(R.string.library_new_playlist)) }
                            }
                        },
                    )
                }
                bins.forEach { bin ->
                items(bin.items, key = { it.id }) { p ->
                    PlaylistRow(
                        playlist = p,
                        count = p.songIds.count { it in state.librarySongIds },
                        art = p.coverPath ?: p.songIds.firstNotNullOfOrNull { artById[it] },
                        onClick = { onOpen(p) },
                        onExport = { export(p) },
                        onEditDescription = { dialog = PlaylistDialog.Description(p) },
                        onChangeCover = {
                            coverTarget = p.id
                            snack(res.getString(R.string.library_choose_square_image))
                            coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onRemoveCover = if (p.coverPath != null) ({ scope.launch { repo.setCover(p.id, null) } }) else null,
                        onRename = { dialog = PlaylistDialog.Rename(p) },
                        onDelete = { dialog = PlaylistDialog.Delete(p) },
                    )
                }
                }
            }
            FastScroller(listState, labels, starts, Modifier.align(Alignment.CenterEnd))
        }
    }

    when (val d = dialog) {
        null -> Unit
        PlaylistDialog.Create -> CreatePlaylistDialog(
            onCreate = { name -> scope.launch { repo.create(name) } },
            onDismiss = { dialog = null },
        )
        is PlaylistDialog.Rename -> RenamePlaylistDialog(
            currentName = d.p.name,
            onRename = { name -> scope.launch { repo.rename(d.p.id, name) } },
            onDismiss = { dialog = null },
        )
        is PlaylistDialog.Description -> EditDescriptionDialog(
            currentDescription = d.p.description,
            onSave = { text -> scope.launch { repo.setDescription(d.p.id, text) } },
            onDismiss = { dialog = null },
        )
        is PlaylistDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.library_delete_title),
            message = stringResource(R.string.library_delete_message, d.p.name),
            confirmLabel = stringResource(R.string.library_delete),
            destructive = true,
            onConfirm = {
                dialog = null
                hidden = hidden + d.p.id
                scope.launch {
                    repo.delete(d.p.id)
                    snack(res.getString(R.string.library_deleted, d.p.name))
                }
            },
            onDismiss = { dialog = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    count: Int,
    art: String?,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onEditDescription: () -> Unit,
    onChangeCover: () -> Unit,
    onRemoveCover: (() -> Unit)?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val labels = listOf(
        R.string.library_export_m3u to onExport,
        R.string.library_edit_description to onEditDescription,
        R.string.library_change_cover to onChangeCover,
    ) + listOfNotNull(onRemoveCover?.let { R.string.library_remove_cover to it }) + listOf(
        R.string.library_rename to onRename,
        R.string.library_delete to onDelete,
    )
    val resolved = labels.map { (id, action) -> stringResource(id) to action }
    Box {
        ListItem(
            modifier = Modifier
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                )
                .semantics { customActions = resolved.map { (l, a) -> CustomAccessibilityAction(l) { a(); true } } },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = { AlbumArt(art, playlist.name, Modifier.size(56.dp), shape = SleeveRowShape, decorative = true) },
            headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(pluralStringResource(R.plurals.song_count, count, count), maxLines = 1) },
            trailingContent = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_more_options)) }
            },
        )
        Box(Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                resolved.forEachIndexed { i, (label, action) ->
                    val destructive = i == resolved.lastIndex
                    DropdownMenuItem(
                        text = { Text(label, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                        onClick = { menu = false; action() },
                    )
                }
            }
        }
    }
}
