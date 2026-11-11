package de.yurtemre.sonora.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.yurtemre.sonora.shared.library.AlbumGrouper
import de.yurtemre.sonora.shared.library.ArtistGrouper
import de.yurtemre.sonora.shared.library.LibrarySearchIndex
import de.yurtemre.sonora.shared.library.LibrarySorter
import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.AlbumSort
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.ArtistSort
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.PlaylistSort
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.model.SongSort
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import de.yurtemre.sonora.shared.repo.impl.LibraryRepositoryImpl
import de.yurtemre.sonora.shared.stats.StatsAggregates
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.navigation.LibraryTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Current sort of one tab; [key] is the sort enum's storage name. */
data class TabSort(val key: String, val ascending: Boolean)

/**
 * Sorted + filtered content of one Home tab. Only the list matching [tab] is filled.
 * [totalCount] is the unfiltered size (for "no results" vs. "empty library").
 */
data class HomeTabState(
    val tab: LibraryTab,
    val query: String = "",
    val sort: TabSort = TabSort("", true),
    val songs: List<Song> = emptyList(),
    val albums: List<AlbumGroup> = emptyList(),
    val artists: List<ArtistGroup> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val totalCount: Int = 0,
    val loaded: Boolean = false,
    /** Library song ids, so playlist counts can hide missing songs (LIB-16). */
    val librarySongIds: Set<Long> = emptySet(),
    /** Play counts per song id, filled only for the "plays" sort (row subtitle). */
    val playCounts: Map<Long, Int> = emptyMap(),
) {
    val count: Int get() = when (tab) {
        LibraryTab.Songs -> songs.size
        LibraryTab.Albums -> albums.size
        LibraryTab.Artists -> artists.size
        LibraryTab.Playlists -> playlists.size
    }
    val totalDurationMs: Long get() = songs.sumOf { it.durationMs }
}

data class HomeHeaderState(val userName: String? = null, val isSyncing: Boolean = false)

class HomeViewModel(
    private val library: LibraryRepository,
    playlistRepo: PlaylistRepository,
    private val settingsRepo: SettingsRepository,
    private val stats: StatsRepository,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    fun setQuery(q: String) { _query.value = q }

    private val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val header: StateFlow<HomeHeaderState> = combine(settingsRepo.settings, library.isSyncing) { s, syncing ->
        HomeHeaderState(s.userName.trim().takeIf { s.useGreetingTitle && it.isNotEmpty() }, syncing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeHeaderState())

    private data class Lib(val songs: List<Song>, val albums: List<AlbumGroup>, val artists: List<ArtistGroup>, val index: LibrarySearchIndex)

    private val lib = combine(library.songs, library.artistImages) { songs, images ->
        val albums = AlbumGrouper.build(songs)
        Lib(songs, albums, ArtistGrouper.build(songs, albums, images), LibrarySearchIndex(songs))
    }.flowOn(Dispatchers.Default)

    private val sorts = settingsRepo.settings.map { it.sorts() }

    val songs: StateFlow<HomeTabState> = combine(lib, _query, sorts, stats.snapshot) { l, q, s, snap ->
        val ts = s.getValue(LibraryTab.Songs)
        val sort = SongSort.fromStorage(ts.key)
        val res = if (sort == SongSort.Plays) {
            // Plays sort goes through the stats activity view (audit/data §6.3), not the title fallback.
            LibrarySorter.sortSongs(l.index.searchSongs(q), sort, ts.ascending) { list, view ->
                StatsAggregates.applyActivityView(snap, list, view)
            }
        } else l.index.searchSongs(q, sort, ts.ascending)
        val counts = if (sort == SongSort.Plays) snap.songs.mapValues { it.value.plays } else emptyMap()
        HomeTabState(LibraryTab.Songs, q, ts, songs = res, totalCount = l.songs.size, loaded = true, playCounts = counts)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeTabState(LibraryTab.Songs))

    val albums: StateFlow<HomeTabState> = combine(lib, _query, sorts) { l, q, s ->
        val ts = s.getValue(LibraryTab.Albums)
        val res = l.index.searchAlbums(l.albums, q, AlbumSort.fromStorage(ts.key), ts.ascending)
        HomeTabState(LibraryTab.Albums, q, ts, albums = res, songs = res.flatMap { it.songs }, totalCount = l.albums.size, loaded = true)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeTabState(LibraryTab.Albums))

    val artists: StateFlow<HomeTabState> = combine(lib, _query, sorts) { l, q, s ->
        val ts = s.getValue(LibraryTab.Artists)
        val res = l.index.searchArtists(l.artists, q, ArtistSort.fromStorage(ts.key), ts.ascending)
        HomeTabState(LibraryTab.Artists, q, ts, artists = res, songs = res.flatMap { it.songs }, totalCount = l.artists.size, loaded = true)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeTabState(LibraryTab.Artists))

    val playlists: StateFlow<HomeTabState> = combine(lib, playlistRepo.playlists, _query, sorts) { l, pls, q, s ->
        val ts = s.getValue(LibraryTab.Playlists)
        val ids = l.songs.mapTo(HashSet()) { it.id }
        val res = LibrarySorter.sortPlaylists(LibrarySearchIndex.filterPlaylists(pls, q), PlaylistSort.fromStorage(ts.key), ts.ascending, ids)
        HomeTabState(LibraryTab.Playlists, q, ts, playlists = res, totalCount = pls.size, loaded = true, librarySongIds = ids)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeTabState(LibraryTab.Playlists))

    /** UI-12 sync banner, computed once at startup (LIB-21). */
    private val _syncBanner = MutableStateFlow(false)
    val syncBanner: StateFlow<Boolean> = _syncBanner.asStateFlow()

    init {
        viewModelScope.launch { _syncBanner.value = (library as? LibraryRepositoryImpl)?.shouldShowSyncReminder() == true }
    }

    /** "Remind me next month": postpone = now + 30 d (LIB-21). */
    fun postponeSyncReminder() {
        _syncBanner.value = false
        viewModelScope.launch { (library as? LibraryRepositoryImpl)?.postponeSyncReminder() }
    }

    /** LIB-04 sync; hides the banner first (UI-12). */
    suspend fun sync(): SyncOutcome {
        _syncBanner.value = false
        return library.sync()
    }

    fun stateFor(tab: LibraryTab): StateFlow<HomeTabState> = when (tab) {
        LibraryTab.Songs -> songs
        LibraryTab.Albums -> albums
        LibraryTab.Artists -> artists
        LibraryTab.Playlists -> playlists
    }

    /** Applies and persists a sort choice. A changed song sort uses [LibrarySorter.directionOnSortChange]. */
    fun setSort(tab: LibraryTab, key: String, ascending: Boolean? = null) {
        val current = settings.value?.sorts()?.get(tab) ?: return
        viewModelScope.launch {
            when (tab) {
                LibraryTab.Songs -> {
                    val prev = SongSort.fromStorage(current.key); val next = SongSort.fromStorage(key)
                    settingsRepo.setSongSort(next, ascending ?: LibrarySorter.directionOnSortChange(prev, next, current.ascending))
                }
                LibraryTab.Albums -> settingsRepo.setAlbumSort(AlbumSort.fromStorage(key), ascending ?: current.ascending)
                LibraryTab.Artists -> settingsRepo.setArtistSort(ArtistSort.fromStorage(key), ascending ?: current.ascending)
                LibraryTab.Playlists -> settingsRepo.setPlaylistSort(PlaylistSort.fromStorage(key), ascending ?: current.ascending)
            }
        }
    }

    private fun AppSettings.sorts(): Map<LibraryTab, TabSort> = mapOf(
        LibraryTab.Songs to TabSort(songSortBy.storageName, songSortAscending),
        LibraryTab.Albums to TabSort(albumSortBy.storageName, albumSortAscending),
        LibraryTab.Artists to TabSort(artistSortBy.storageName, artistSortAscending),
        LibraryTab.Playlists to TabSort(playlistSortBy.storageName, playlistSortAscending),
    )
}
