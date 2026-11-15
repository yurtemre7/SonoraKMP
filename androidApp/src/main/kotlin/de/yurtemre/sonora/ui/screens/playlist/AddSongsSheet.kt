package de.yurtemre.sonora.ui.screens.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import de.yurtemre.sonora.ui.theme.PillShape
import de.yurtemre.sonora.ui.components.displayTitle
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
    val focus = LocalFocusManager.current
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
            // Pill search field like the Home search; the clear button empties it and closes the keyboard.
            TextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    AnimatedVisibility(visible = query.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                        IconButton(onClick = { query = ""; focus.clearFocus() }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.home_search_clear))
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.playlist_add_search)) },
                singleLine = true,
                shape = PillShape,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp)) // breathing room between the search field and the list
            Box(Modifier.weight(1f, fill = false)) {
                if (visible.isEmpty()) {
                    Text(
                        stringResource(R.string.playlist_add_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(visible, key = { it.id }) { s ->
                        SongRow(
                            title = s.displayTitle(), artist = s.artist, durationMs = s.durationMs,
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
