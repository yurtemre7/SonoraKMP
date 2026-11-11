package de.yurtemre.sonora.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.theme.SleeveRowShape
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Playlist selector (UI-29). Single mode (one song): rows containing the song are tinted with a check and
 * a tap toggles membership optimistically (snackbar inside the sheet). Multi mode: a tap adds every song,
 * closes and reports via [onMessage]. "New playlist" expands an inline field; creating adds the song(s).
 */
@Composable
fun PlaylistSelectorSheet(
    songIds: List<Long>,
    playlists: List<Playlist>,
    onAdd: (playlist: Playlist, songIds: List<Long>) -> Unit,
    onRemove: (playlist: Playlist, songIds: List<Long>) -> Unit,
    onCreate: (name: String, songIds: List<Long>) -> Unit,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit = {},
) {
    val multi = songIds.size != 1
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val res = LocalContext.current.resources
    val snackbar = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Optimistic membership overrides (single mode) until the repository flow catches up.
    val overrides = remember { mutableStateMapOf<String, Boolean>() }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    fun close(msg: String?) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            msg?.let(onMessage)
        }
    }
    fun snack(msg: String) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(msg)
    }
    fun create() {
        val name = newName.trim()
        if (name.isEmpty()) return
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onCreate(name, songIds)
        newName = ""
        creating = false
        if (multi) close(res.getQuantityString(R.plurals.sheets_selector_batch_added, songIds.size, songIds.size, name))
        else snack(res.getString(R.string.sheets_selector_added, name))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Box {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    if (multi) pluralStringResource(R.plurals.sheets_selector_title_multi, songIds.size, songIds.size)
                    else stringResource(R.string.sheets_selector_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(role = Role.Button) { creating = !creating }
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, SleeveRowShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                    Text(stringResource(R.string.sheets_selector_new), style = MaterialTheme.typography.titleMedium)
                }
                AnimatedVisibility(creating) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text(stringResource(R.string.sheets_dialog_name_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { create() }),
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = ::create, enabled = newName.isNotBlank(), shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.sheets_dialog_create))
                        }
                    }
                }
                if (playlists.isEmpty()) {
                    Text(
                        stringResource(R.string.sheets_selector_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(playlists, key = { it.id }) { p ->
                        val contains = !multi && (overrides[p.id] ?: (songIds.first() in p.songIds))
                        SelectorRow(p, contains, showState = !multi) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            if (multi) {
                                onAdd(p, songIds)
                                close(res.getQuantityString(R.plurals.sheets_selector_batch_added, songIds.size, songIds.size, p.name))
                            } else if (contains) {
                                overrides[p.id] = false
                                onRemove(p, songIds)
                                snack(res.getString(R.string.sheets_selector_removed, p.name))
                            } else {
                                overrides[p.id] = true
                                onAdd(p, songIds)
                                snack(res.getString(R.string.sheets_selector_added, p.name))
                            }
                        }
                    }
                    item { Spacer(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

@Composable
private fun SelectorRow(p: Playlist, contains: Boolean, showState: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (contains) cs.primaryContainer else Color.Transparent,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "selectorBg",
    )
    val state = stringResource(if (contains) R.string.sheets_selector_contains else R.string.sheets_selector_not_contains)
    val count = pluralStringResource(R.plurals.song_count, p.songIds.size, p.songIds.size)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(bg)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { if (showState) stateDescription = state }
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AlbumArt(p.coverPath, p.name, Modifier.size(48.dp), shape = SleeveRowShape, decorative = true)
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        if (showState) {
            Icon(
                if (contains) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (contains) cs.primary else cs.outline,
            )
        }
    }
}

/** [PlaylistSelectorSheet] wired to [PlaylistRepository] via Koin. */
@Composable
fun PlaylistSelectorSheetHost(songIds: List<Long>, onDismiss: () -> Unit, onMessage: (String) -> Unit = {}) {
    val repo: PlaylistRepository = koinInject()
    val playlists by repo.playlists.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    PlaylistSelectorSheet(
        songIds = songIds,
        playlists = playlists,
        onAdd = { p, ids -> scope.launch(NonCancellable) { repo.addSongs(p.id, ids) } },
        onRemove = { p, ids -> scope.launch(NonCancellable) { repo.removeSongs(p.id, ids) } },
        onCreate = { name, ids -> scope.launch(NonCancellable) { repo.create(name, ids) } },
        onDismiss = onDismiss,
        onMessage = onMessage,
    )
}
