package de.yurtemre.sonora.shared.stats

import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.model.SongActivityView
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot

data class Ranked<T>(val item: T, val plays: Int)

/** Computed stats over a [StatsSnapshot] (STAT-03/04, audit/data §11.2). Groups come from the library. */
object StatsAggregates {
    val songComparator: Comparator<Song> = compareBy<Song>({ it.titleLower }, { it.artistLower }, { it.id })

    fun totalSkips(s: StatsSnapshot): Int = s.songs.values.sumOf { it.skips }
    fun totalRestarts(s: StatsSnapshot): Int = s.songs.values.sumOf { it.restarts }
    fun uniqueSongsPlayed(s: StatsSnapshot): Int = s.songs.values.count { it.plays > 0 }
    fun playlistsWithPlays(s: StatsSnapshot): Int = s.playlistPlays.values.count { it > 0 }
    fun plays(s: StatsSnapshot, songId: Long): Int = s.songs[songId]?.plays ?: 0

    fun albumListenCount(s: StatsSnapshot, albums: List<AlbumGroup>): Int =
        albums.count { a -> a.songs.any { plays(s, it.id) > 0 } }

    fun artistListenCount(s: StatsSnapshot, artists: List<ArtistGroup>): Int =
        artists.count { a -> a.songs.any { plays(s, it.id) > 0 } }

    fun firstPlayedSong(s: StatsSnapshot, library: List<Song>): Song? =
        s.firstPlayedSongId?.let { id -> library.firstOrNull { it.id == id } }

    /** Weekday key (Sun=0) with max count; ties → lowest key; null when none (UI shows `—`). */
    fun mostActiveDay(s: StatsSnapshot): Int? =
        s.weekdayCounts.filterValues { it > 0 }.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key

    fun topSongs(s: StatsSnapshot, library: List<Song>, limit: Int = 5): List<Ranked<Song>> {
        val byId = library.associateBy { it.id }
        return s.songs.entries.filter { it.value.plays > 0 }
            .mapNotNull { e -> byId[e.key]?.let { Ranked(it, e.value.plays) } }
            .sortedByDescending { it.plays }.take(limit)
    }

    fun topAlbums(s: StatsSnapshot, albums: List<AlbumGroup>, limit: Int = 5): List<Ranked<AlbumGroup>> =
        rankGroups(albums, limit) { a -> a.songs.sumOf { plays(s, it.id) } }

    fun topArtists(s: StatsSnapshot, artists: List<ArtistGroup>, limit: Int = 5): List<Ranked<ArtistGroup>> =
        rankGroups(artists, limit) { a -> a.songs.sumOf { plays(s, it.id) } }

    fun topPlaylists(s: StatsSnapshot, playlists: List<Playlist>, limit: Int = 5): List<Ranked<Playlist>> {
        val byId = playlists.associateBy { it.id }
        return s.playlistPlays.entries.filter { it.value > 0 }
            .mapNotNull { e -> byId[e.key]?.let { Ranked(it, e.value) } }
            .sortedByDescending { it.plays }.take(limit)
    }

    fun recentSongs(s: StatsSnapshot, library: List<Song>, limit: Int? = null): List<Song> {
        val r = library.filter { s.songs[it.id]?.lastPlayedAtMs != null }
            .sortedWith(compareByDescending<Song> { s.songs[it.id]!!.lastPlayedAtMs!! }.then(songComparator))
        return if (limit == null) r else r.take(limit)
    }

    fun applyActivityView(s: StatsSnapshot, songs: List<Song>, view: SongActivityView): List<Song> = when (view) {
        SongActivityView.All -> songs
        SongActivityView.RecentlyPlayed -> recentSongs(s, songs)
        SongActivityView.MostPlayed -> songs.sortedWith(compareByDescending<Song> { plays(s, it.id) }.then(songComparator))
        SongActivityView.LeastPlayed -> songs.sortedWith(compareBy<Song> { plays(s, it.id) }.then(songComparator))
    }

    private inline fun <T> rankGroups(groups: List<T>, limit: Int, sum: (T) -> Int): List<Ranked<T>> =
        groups.map { Ranked(it, sum(it)) }.filter { it.plays > 0 }.sortedByDescending { it.plays }.take(limit)
}
