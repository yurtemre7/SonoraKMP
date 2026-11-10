package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.AlbumSort
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.ArtistSort
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.model.SongSort

/**
 * Library search (LIB-13, audit/data §6). Songs: AND of whitespace terms, substring of
 * title/artist/album; results ordered by the chosen sort. Not thread-safe.
 */
class LibrarySearchIndex(songs: List<Song> = emptyList()) {
    private var songs: List<Song> = songs
    private val sortedCache = HashMap<String, List<Song>>()
    private val folded = HashMap<Long, Triple<String, String, String>>()

    fun build(songs: List<Song>) {
        this.songs = songs
        sortedCache.clear()
        folded.clear()
    }

    fun getSortedSongs(sort: SongSort, ascending: Boolean): List<Song> =
        sortedCache.getOrPut("${sort.storageName}_$ascending") { LibrarySorter.sortSongs(songs, sort, ascending) }

    fun searchSongs(query: String, sort: SongSort = SongSort.Title, ascending: Boolean = true): List<Song> {
        val q = normalizeForSearch(query.trim())
        if (q.isEmpty()) return getSortedSongs(sort, ascending)
        val terms = q.split(WHITESPACE).filter { it.isNotEmpty() }
        val matches = songs.filter { s ->
            val fields = folded.getOrPut(s.id) { Triple(normalizeForSearch(s.title), normalizeForSearch(s.artist), normalizeForSearch(s.album)) }
            terms.all { t -> fields.first.contains(t) || fields.second.contains(t) || fields.third.contains(t) }
        }
        return LibrarySorter.sortSongs(matches, sort, ascending)
    }

    fun searchAlbums(albums: List<AlbumGroup>, query: String, sort: AlbumSort = AlbumSort.Name, ascending: Boolean = true): List<AlbumGroup> {
        val q = normalizeForSearch(query.trim())
        val filtered = if (q.isEmpty()) albums else albums.filter { normalizeForSearch(it.name).contains(q) || normalizeForSearch(it.artist).contains(q) }
        return LibrarySorter.sortAlbums(filtered, sort, ascending)
    }

    fun searchArtists(artists: List<ArtistGroup>, query: String, sort: ArtistSort = ArtistSort.Name, ascending: Boolean = true): List<ArtistGroup> {
        val q = normalizeForSearch(query.trim())
        val filtered = if (q.isEmpty()) artists else artists.filter { normalizeForSearch(it.name).contains(q) }
        return LibrarySorter.sortArtists(filtered, sort, ascending)
    }

    companion object {
        private val WHITESPACE = Regex("""\s+""")

        fun filterPlaylists(playlists: List<Playlist>, query: String): List<Playlist> {
            val q = query.trim().lowercase()
            return if (q.isEmpty()) playlists else playlists.filter { it.name.lowercase().contains(q) }
        }
    }
}
