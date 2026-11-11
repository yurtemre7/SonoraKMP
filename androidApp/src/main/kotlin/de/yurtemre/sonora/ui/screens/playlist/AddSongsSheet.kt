package de.yurtemre.sonora.ui.screens.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.ui.components.SongRow
import org.koin.compose.koinInject

/** Library picker for adding songs to a playlist; songs already in it are hidden. */
@Composable
internal fun AddSongsSheet(existing: Set<Long>, onAdd: (List<Long>) -> Unit, onDismiss: () -> Unit) {
    val library: LibraryRepository = koinInject()
    val songs by library.songs.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf(linkedSetOf<Long>()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val visible = remember(songs, query, existing) {
        val q = query.trim().lowercase()
        songs.filter { it.id !in existing && (q.isEmpty() || q in it.titleLower || q in it.artistLower || q in it.albumLower) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.playlist_add_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(stringResource(R.string.playlist_add_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            Box(Modifier.weight(1f, fill = false)) {
                if (visible.isEmpty()) {
                    Text(
                        stringResource(R.string.playlist_add_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyColumn {
                    items(visible, key = { it.id }) { s ->
                        SongRow(
                            title = s.title, artist = s.artist, durationMs = s.durationMs,
                            artwork = s.artworkPath, showArtwork = true,
                            selected = s.id in picked,
                            onClick = { picked = LinkedHashSet(picked).apply { if (!remove(s.id)) add(s.id) } },
                        )
                    }
                }
            }
            Button(
                onClick = { onAdd(picked.toList()); onDismiss() },
                enabled = picked.isNotEmpty(),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).navigationBarsPadding(),
            ) {
                Text(pluralStringResource(R.plurals.playlist_add_confirm, picked.size, picked.size))
            }
        }
    }
}
