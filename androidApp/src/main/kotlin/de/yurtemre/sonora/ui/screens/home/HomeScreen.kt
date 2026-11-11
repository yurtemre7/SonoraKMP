package de.yurtemre.sonora.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import de.yurtemre.sonora.ui.navigation.LibraryTab

/** Slot for a tab's body; P5-04 plugs the real tabs in. */
/** Emits a tab when its nav item is selected again (scroll to top). */
object HomeTabReselect {
    val events = kotlinx.coroutines.flow.MutableSharedFlow<LibraryTab>(extraBufferCapacity = 1)
}

typealias HomeTabContent = @Composable (LibraryTab, HomeTabState) -> Unit

/** Home scaffold (DESIGN §6b): header with in-place search, per-tab sort sheet, [tabContent] body. */
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
    onShuffle: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    tabContent: HomeTabContent = { t, s -> PlaceholderTabContent(t, s) },
) {
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val pull = remember(threshold) {
        PullToSearch(threshold) {
            if (!searchOpen) { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate); searchOpen = true }
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            HomeHeader(
                tab = tab, state = state, userName = header.userName, isSyncing = header.isSyncing,
                searchOpen = searchOpen, query = query, onQueryChange = onQueryChange,
                onOpenSearch = { searchOpen = true },
                onCloseSearch = { onQueryChange(""); searchOpen = false },
                onSortClick = { sortOpen = true },
                onShuffle = onShuffle,
                onRecentlyPlayed = onRecentlyPlayed, onFavorites = onFavorites, onSettings = onSettings,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).nestedScroll(pull)) {
            tabContent(tab, state)
        }
    }
    if (sortOpen) SortSheet(tab, state.sort, onSortSelect, onSortDirection, onDismiss = { sortOpen = false })
}

/** Pulling the list down past its top by [thresholdPx] opens search (once per gesture). */
private class PullToSearch(private val thresholdPx: Float, private val onTrigger: () -> Unit) : NestedScrollConnection {
    private var pulled = 0f
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y > 0f && consumed.y == 0f) {
            pulled += available.y
            if (pulled > thresholdPx) { pulled = Float.NEGATIVE_INFINITY; onTrigger() }
        } else if (available.y < 0f || consumed.y != 0f) {
            if (pulled.isFinite()) pulled = 0f
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity { pulled = 0f; return Velocity.Zero }
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
