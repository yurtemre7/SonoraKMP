package de.yurtemre.sonora.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.SongSort
import de.yurtemre.sonora.ui.format.DurationFormat
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.navigation.labelRes
import de.yurtemre.sonora.ui.theme.PillShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration

/** Pill search field + sort button + result count (UI-05). Buttons hide while the field is focused. */
@Composable
fun SearchHeader(
    tab: LibraryTab,
    state: HomeTabState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSortClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).onFocusChanged { focused = it.isFocused },
                placeholder = { Text(stringResource(R.string.home_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.home_search_clear)) } }
                } else null,
                singleLine = true,
                shape = PillShape,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f),
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
            if (!focused) {
                val plays = tab == LibraryTab.Songs && state.sort.key == SongSort.Plays.storageName
                val label = stringResource(R.string.home_sort_button, stringResource(tab.labelRes))
                FilledTonalIconButton(
                    onClick = onSortClick,
                    colors = if (plays) IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ) else IconButtonDefaults.filledTonalIconButtonColors(
                        // Tonal default is secondaryContainer (Label yellow): neutral unless sort = plays.
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = label) }
            }
        }
        Text(countLabel(tab, state), style = MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun countLabel(tab: LibraryTab, s: HomeTabState): String = when (tab) {
    LibraryTab.Songs -> {
        val ctx = LocalContext.current
        @Suppress("DEPRECATION") val locale = LocalConfiguration.current.locales[0]
        stringResource(R.string.home_count_with_duration,
            pluralStringResource(R.plurals.song_count, s.count, s.count),
            DurationFormat.totalText(ctx, s.totalDurationMs / 1000, locale))
    }
    LibraryTab.Albums -> pluralStringResource(R.plurals.album_count, s.count, s.count)
    LibraryTab.Artists -> pluralStringResource(R.plurals.artist_count, s.count, s.count)
    LibraryTab.Playlists -> pluralStringResource(R.plurals.playlist_count, s.count, s.count)
}
