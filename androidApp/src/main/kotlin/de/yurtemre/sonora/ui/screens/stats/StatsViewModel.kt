package de.yurtemre.sonora.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.yurtemre.sonora.shared.library.AlbumGrouper
import de.yurtemre.sonora.shared.library.ArtistGrouper
import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.stats.Ranked
import de.yurtemre.sonora.shared.stats.StatsAggregates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StatsUi(
    val loaded: Boolean = false,
    val completeListens: Int = 0,
    val uniqueSongs: Int = 0,
    val albumsHeard: Int = 0,
    val artistsHeard: Int = 0,
    val playlistsPlayed: Int = 0,
    val shuffleSessions: Int = 0,
    val skips: Int = 0,
    val restarts: Int = 0,
    /** Sun=0..Sat=6. */
    val mostActiveDay: Int? = null,
    val firstSong: Song? = null,
    /** yyyy-MM-dd. */
    val firstDate: String? = null,
    val topSongs: List<Ranked<Song>> = emptyList(),
    val songDetail: Map<Long, SongStats> = emptyMap(),
    val topAlbums: List<Ranked<AlbumGroup>> = emptyList(),
    val topArtists: List<Ranked<ArtistGroup>> = emptyList(),
    val topPlaylists: List<Ranked<Playlist>> = emptyList(),
    /** Playlist id → songs that still exist (FIX D-18). */
    val playlistSongCounts: Map<String, Int> = emptyMap(),
    val playlistCovers: Map<String, String?> = emptyMap(),
) {
    val isEmpty: Boolean get() = loaded && completeListens == 0 && uniqueSongs == 0 && skips == 0 && firstSong == null
}

/** STAT-05/06 view model; all aggregation runs off the main thread. */
class StatsViewModel(
    library: LibraryRepository,
    playlists: PlaylistRepository,
    private val stats: StatsRepository,
) : ViewModel() {
    val ui: StateFlow<StatsUi> = combine(
        library.songs, library.artistImages, playlists.playlists, stats.snapshot,
    ) { songs, images, lists, s ->
        val albums = AlbumGrouper.build(songs)
        val artists = ArtistGrouper.build(songs, albums, images)
        val byId = songs.associateBy { it.id }
        val topSongs = StatsAggregates.topSongs(s, songs)
        val topPlaylists = StatsAggregates.topPlaylists(s, lists)
        StatsUi(
            loaded = true,
            completeListens = s.completeListens,
            uniqueSongs = StatsAggregates.uniqueSongsPlayed(s),
            albumsHeard = StatsAggregates.albumListenCount(s, albums),
            artistsHeard = StatsAggregates.artistListenCount(s, artists),
            playlistsPlayed = StatsAggregates.playlistsWithPlays(s),
            shuffleSessions = s.shuffleSessions,
            skips = StatsAggregates.totalSkips(s),
            restarts = StatsAggregates.totalRestarts(s),
            mostActiveDay = StatsAggregates.mostActiveDay(s),
            firstSong = StatsAggregates.firstPlayedSong(s, songs),
            firstDate = s.firstPlayedDate,
            topSongs = topSongs,
            songDetail = topSongs.associate { it.item.id to (s.songs[it.item.id] ?: SongStats()) },
            topAlbums = StatsAggregates.topAlbums(s, albums),
            topArtists = StatsAggregates.topArtists(s, artists),
            topPlaylists = topPlaylists,
            playlistSongCounts = topPlaylists.associate { r -> r.item.id to r.item.songIds.count { it in byId } },
            playlistCovers = topPlaylists.associate { r ->
                r.item.id to (r.item.coverPath ?: r.item.songIds.firstNotNullOfOrNull { byId[it]?.artworkPath })
            },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUi())

    fun reset() { viewModelScope.launch { stats.reset() } }
}
