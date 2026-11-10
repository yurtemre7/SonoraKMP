package de.yurtemre.sonora.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.ui.navigation.LibraryTab

/** Slot for a tab's body; P5-04 plugs the real tabs in. */
typealias HomeTabContent = @Composable (LibraryTab, HomeTabState) -> Unit

/** Home scaffold (UI-04..06): collapsing header, shared search, per-tab sort sheet, [tabContent] body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    tab: LibraryTab,
    header: HomeHeaderState,
    state: HomeTabState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSortSelect: (String) -> Unit,
    onSortDirection: (Boolean) -> Unit,
    onRecentlyPlayed: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    tabContent: HomeTabContent = { t, s -> PlaceholderTabContent(t, s) },
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { HomeHeader(header.userName, header.isSyncing, scroll, onRecentlyPlayed, onFavorites, onSettings) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            SearchHeader(tab, state, query, onQueryChange, onSortClick = { sortOpen = true })
            tabContent(tab, state)
        }
    }
    if (sortOpen) SortSheet(tab, state.sort, onSortSelect, onSortDirection, onDismiss = { sortOpen = false })
}

/** Plain list until the real tab composables land. */
@Composable
fun PlaceholderTabContent(tab: LibraryTab, state: HomeTabState) {
    val labels: List<Pair<Any, String>> = when (tab) {
        LibraryTab.Songs -> state.songs.map { it.id to it.title }
        LibraryTab.Albums -> state.albums.map { it.key() to it.name }
        LibraryTab.Artists -> state.artists.map { it.key() to it.name }
        LibraryTab.Playlists -> state.playlists.map { it.id to it.name }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(labels, key = { it.first }) { (_, label) ->
            Text(label, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}
