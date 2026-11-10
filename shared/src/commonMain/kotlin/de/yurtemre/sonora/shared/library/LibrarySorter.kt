package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.FavoritesSort
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.model.SongActivityView
import de.yurtemre.sonora.shared.model.SongSort
import de.yurtemre.sonora.shared.model.AlbumSort
import de.yurtemre.sonora.shared.model.ArtistSort
import de.yurtemre.sonora.shared.model.PlaylistSort

/** Hook into the stats service's `applyActivityView` (audit/data §6.3). */
typealias ActivityViewHook = (songs: List<Song>, view: SongActivityView) -> List<Song>

/** Sorting (LIB-12, audit/data §5.4). All sorts are stable; descending negates the whole comparison. */
object LibrarySorter {
    private fun <T> Comparator<T>.dir(ascending: Boolean): Comparator<T> = if (ascending) this else reversed()

    fun songComparator(sort: SongSort, ascending: Boolean): Comparator<Song> {
        val base: Comparator<Song> = when (sort) {
            SongSort.Artist -> compareBy<Song> { it.artistLower }.thenBy { it.titleLower }
            SongSort.Duration -> compareBy<Song> { it.durationMs }.thenBy { it.titleLower }
            SongSort.Recent -> compareByDescending<Song> { it.lastModifiedMs ?: 0L }.thenBy { it.titleLower }
            else -> compareBy { it.titleLower }
        }
        return base.thenBy { it.id }.dir(ascending)
    }

    /** `plays` = title asc, then activity view (mostPlayed when descending, leastPlayed when ascending). */
    fun sortSongs(songs: List<Song>, sort: SongSort, ascending: Boolean, activityView: ActivityViewHook? = null): List<Song> {
        if (sort == SongSort.Plays) {
            val base = songs.sortedWith(songComparator(SongSort.Title, true))
            val view = if (ascending) SongActivityView.LeastPlayed else SongActivityView.MostPlayed
            return activityView?.invoke(base, view) ?: base
        }
        return songs.sortedWith(songComparator(sort, ascending))
    }

    /** Direction to apply when the user switches sort: entering `plays` → descending, leaving it → ascending. */
    fun directionOnSortChange(previous: SongSort, next: SongSort, currentAscending: Boolean): Boolean = when {
        next == SongSort.Plays && previous != SongSort.Plays -> false
        previous == SongSort.Plays && next != SongSort.Plays -> true
        else -> currentAscending
    }

    fun latestModifiedMs(album: AlbumGroup): Long = album.songs.maxOfOrNull { it.lastModifiedMs ?: 0L } ?: 0L

    fun sortAlbums(albums: List<AlbumGroup>, sort: AlbumSort, ascending: Boolean): List<AlbumGroup> {
        val c: Comparator<AlbumGroup> = when (sort) {
            AlbumSort.Name -> compareBy { it.name.lowercase() }
            AlbumSort.Artist -> compareBy { it.artist.lowercase() }
            AlbumSort.Tracks -> compareBy { it.songs.size }
            AlbumSort.Recent -> compareByDescending { latestModifiedMs(it) }
        }
        return albums.sortedWith(c.dir(ascending))
    }

    /** `songs` sorts by song count (FIX D-23; Flutter fell back to name). */
    fun sortArtists(artists: List<ArtistGroup>, sort: ArtistSort, ascending: Boolean): List<ArtistGroup> {
        val c: Comparator<ArtistGroup> = when (sort) {
            ArtistSort.Name -> compareBy { it.name.lowercase() }
            ArtistSort.Albums -> compareBy { it.albums.size }
            ArtistSort.Songs -> compareBy { it.songs.size }
        }
        return artists.sortedWith(c.dir(ascending))
    }

    fun sortPlaylists(playlists: List<Playlist>, sort: PlaylistSort, ascending: Boolean, librarySongIds: Set<Long>): List<Playlist> {
        val c: Comparator<Playlist> = when (sort) {
            PlaylistSort.Name -> compareBy { it.name.lowercase() }
            PlaylistSort.Songs -> compareBy<Playlist> { p -> p.songIds.count { it in librarySongIds } }.thenBy { it.name.lowercase() }
        }
        return playlists.sortedWith(c.dir(ascending))
    }

    fun sortFavoriteSongs(songs: List<Song>, sort: FavoritesSort, ascending: Boolean): List<Song> {
        val c: Comparator<Song> = when (sort) {
            FavoritesSort.Duration -> compareBy { it.durationMs }
            FavoritesSort.Date -> compareBy { it.favoriteDateMs ?: 0L }
            FavoritesSort.Name -> compareBy { it.titleLower }
        }
        return songs.sortedWith(c.dir(ascending))
    }

    /** [favoriteDates] keyed by [AlbumGroup.key]. */
    fun sortFavoriteAlbums(albums: List<AlbumGroup>, sort: FavoritesSort, ascending: Boolean, favoriteDates: Map<String, Long>): List<AlbumGroup> {
        val c: Comparator<AlbumGroup> = when (sort) {
            FavoritesSort.Duration -> compareBy { a -> a.songs.sumOf { it.durationMs } }
            FavoritesSort.Date -> compareBy { favoriteDates[it.key()] ?: 0L }
            FavoritesSort.Name -> compareBy { it.name.lowercase() }
        }
        return albums.sortedWith(c.dir(ascending))
    }

    /** [favoriteDates] keyed by [ArtistGroup.key]. */
    fun sortFavoriteArtists(artists: List<ArtistGroup>, sort: FavoritesSort, ascending: Boolean, favoriteDates: Map<String, Long>): List<ArtistGroup> {
        val c: Comparator<ArtistGroup> = when (sort) {
            FavoritesSort.Duration -> compareBy { a -> a.songs.sumOf { it.durationMs } }
            FavoritesSort.Date -> compareBy { favoriteDates[it.key()] ?: 0L }
            FavoritesSort.Name -> compareBy { it.name.lowercase() }
        }
        return artists.sortedWith(c.dir(ascending))
    }
}
