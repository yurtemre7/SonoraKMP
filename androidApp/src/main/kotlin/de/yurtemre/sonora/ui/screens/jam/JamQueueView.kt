package de.yurtemre.sonora.ui.screens.jam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.jam.JamQueueItem
import de.yurtemre.sonora.jam.JamUiState
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.ui.components.SongRow
import de.yurtemre.sonora.ui.components.displayTitle
import org.koin.compose.koinInject

/**
 * A guest's "Up next": the host's queue, read-only (no reorder or remove). Songs this phone lacks are greyed with
 * "Not on this phone" (they are skipped silently). "Add a song" shows when the host allows it.
 */
@Composable
fun JamQueueView(state: JamUiState.Joined, onAdd: (Song) -> Unit, modifier: Modifier = Modifier) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.jam_queue_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (state.canAdd) {
            FilledTonalButton(onClick = { picking = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null)
                Text(stringResource(R.string.jam_add_song), Modifier.padding(start = 8.dp))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(state.queue) { i, item -> JamQueueRow(item, current = i == state.index) }
        }
    }
    if (picking) JamAddSongSheet(onDismiss = { picking = false }, onPick = { picking = false; onAdd(it) })
}

@Composable
private fun JamQueueRow(item: JamQueueItem, current: Boolean) {
    val note = when {
        !item.available -> stringResource(R.string.jam_not_on_phone)
        current -> stringResource(R.string.jam_now)
        else -> null
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp)
            .alpha(if (item.available) 1f else 0.5f)
            .semantics { if (note != null) stateDescription = note },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val cs = MaterialTheme.colorScheme
        Text(
            item.song.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (current) cs.primary else cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(item.song.artist.takeIf { it.isNotBlank() }, note).joinToString("  "),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Pick a song from this phone's library to send to the host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JamAddSongSheet(onDismiss: () -> Unit, onPick: (Song) -> Unit) {
    val library: LibraryRepository = koinInject()
    val songs by library.songs.collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(songs, query) {
        val q = query.trim()
        if (q.isEmpty()) songs else songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                stringResource(R.string.jam_add_song),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.jam_add_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth()) {
                items(shown, key = { it.id }) { song ->
                    SongRow(
                        title = song.displayTitle(),
                        artist = song.artist,
                        durationMs = song.durationMs,
                        onClick = { onPick(song) },
                        artwork = song.artworkPath,
                        showArtwork = true,
                    )
                }
            }
        }
    }
}
