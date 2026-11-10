package de.yurtemre.sonora.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.AlbumSort
import de.yurtemre.sonora.shared.model.ArtistSort
import de.yurtemre.sonora.shared.model.PlaylistSort
import de.yurtemre.sonora.shared.model.SongSort
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.navigation.labelRes

/** Sort options per tab (audit/ui §2.3): storage key → label. */
internal fun sortOptions(tab: LibraryTab): List<Pair<String, Int>> = when (tab) {
    LibraryTab.Songs -> SongSort.entries.map { it.storageName to when (it) {
        SongSort.Title -> R.string.home_sort_opt_title
        SongSort.Artist -> R.string.home_sort_opt_artist
        SongSort.Duration -> R.string.home_sort_opt_duration
        SongSort.Recent -> R.string.home_sort_opt_recent
        SongSort.Plays -> R.string.home_sort_opt_plays
    } }
    LibraryTab.Albums -> AlbumSort.entries.map { it.storageName to when (it) {
        AlbumSort.Name -> R.string.home_sort_opt_name
        AlbumSort.Artist -> R.string.home_sort_opt_artist
        AlbumSort.Tracks -> R.string.home_sort_opt_tracks
        AlbumSort.Recent -> R.string.home_sort_opt_recent
    } }
    LibraryTab.Artists -> ArtistSort.entries.map { it.storageName to when (it) {
        ArtistSort.Name -> R.string.home_sort_opt_name
        ArtistSort.Albums -> R.string.home_sort_opt_albums
        ArtistSort.Songs -> R.string.home_sort_opt_songs
    } }
    LibraryTab.Playlists -> PlaylistSort.entries.map { it.storageName to when (it) {
        PlaylistSort.Name -> R.string.home_sort_opt_name
        PlaylistSort.Songs -> R.string.home_sort_opt_songs
    } }
}

/**
 * Per-tab sort sheet (UI-06). Choices apply immediately ([onSelect] key, [onDirection] ascending).
 * Songs sorted by plays shows Most/Least played instead of Ascending/Descending.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(
    tab: LibraryTab,
    sort: TabSort,
    onSelect: (String) -> Unit,
    onDirection: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.home_sort_sheet_title, stringResource(tab.labelRes).lowercase()),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(stringResource(R.string.home_sort_saved), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))

            val plays = tab == LibraryTab.Songs && sort.key == SongSort.Plays.storageName
            // Plays: descending = most played (LibrarySorter.sortSongs).
            val segments: List<Pair<Boolean, Int>> = if (plays)
                listOf(false to R.string.home_sort_most_played, true to R.string.home_sort_least_played)
            else listOf(true to R.string.home_sort_ascending, false to R.string.home_sort_descending)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                segments.forEachIndexed { i, (asc, label) ->
                    SegmentedButton(
                        selected = sort.ascending == asc,
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onDirection(asc) },
                        shape = SegmentedButtonDefaults.itemShape(i, segments.size),
                    ) { Text(stringResource(label)) }
                }
            }

            Column(Modifier.selectableGroup()) {
                sortOptions(tab).forEach { (key, label) -> SortOption(label, key == sort.key) {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(key)
                } }
            }
        }
    }
}

@Composable
private fun SortOption(@StringRes label: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}
