package de.yurtemre.sonora.ui.screens.home

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import de.yurtemre.sonora.ui.screens.home.tabs.SongsTab
import de.yurtemre.sonora.ui.screens.home.tabs.AlbumsTab
import de.yurtemre.sonora.ui.screens.home.tabs.ArtistsTab
import de.yurtemre.sonora.ui.screens.home.tabs.PlaylistsTab
import de.yurtemre.sonora.ui.navigation.Album
import de.yurtemre.sonora.ui.navigation.Artist
import de.yurtemre.sonora.ui.navigation.Playlist
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.ui.navigation.Favorites
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.navigation.RecentlyPlayed
import de.yurtemre.sonora.ui.navigation.Settings
import org.koin.compose.koinInject
import kotlinx.coroutines.launch

/**
 * Home (library) route. The ViewModel is activity-scoped so the search query is shared across
 * all tabs of the single `Library` destination.
 */
@Composable
fun HomeRoute(
    tab: LibraryTab,
    onNavigate: (NavKey) -> Unit,
    tabContent: HomeTabContent? = null,
) {
    val owner = (LocalActivity.current as? ViewModelStoreOwner) ?: checkNotNull(LocalViewModelStoreOwner.current)
    val library = koinInject<de.yurtemre.sonora.shared.repo.api.LibraryRepository>()
    val playlists = koinInject<de.yurtemre.sonora.shared.repo.api.PlaylistRepository>()
    val settings = koinInject<de.yurtemre.sonora.shared.settings.SettingsRepository>()
    val stats = koinInject<de.yurtemre.sonora.shared.repo.api.StatsRepository>()
    val vm = viewModel(owner) { HomeViewModel(library, playlists, settings, stats) }
    val snackbar = remember { SnackbarHostState() }
    val playback = koinInject<de.yurtemre.sonora.playback.api.PlaybackConnection>()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val header by vm.header.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.stateFor(tab).collectAsStateWithLifecycle()
    HomeScreen(
        tab = tab, header = header, state = state, query = query,
        onQueryChange = vm::setQuery,
        onSortSelect = { vm.setSort(tab, it) },
        onSortDirection = { vm.setSort(tab, state.sort.key, it) },
        onRecentlyPlayed = { onNavigate(RecentlyPlayed) },
        onFavorites = { onNavigate(Favorites) },
        onSettings = { onNavigate(Settings) },
        snackbarHostState = snackbar,
        onShuffle = {
            if (tab == LibraryTab.Playlists) scope.launch { playback.playShuffled(vm.playlistSongs()) }
            else playback.playShuffled(state.songs)
        },
        tabContent = tabContent ?: { t, s ->
            when (t) {
                LibraryTab.Songs -> SongsTab(s, vm, header.isSyncing, snackbar, onNavigate)
                LibraryTab.Albums -> AlbumsTab(s, snackbar) { onNavigate(Album(it.name, it.artist)) }
                LibraryTab.Artists -> ArtistsTab(s) { onNavigate(Artist(it.name)) }
                LibraryTab.Playlists -> PlaylistsTab(s, snackbar) { onNavigate(Playlist(it.id)) }
            }
        },
    )
}
