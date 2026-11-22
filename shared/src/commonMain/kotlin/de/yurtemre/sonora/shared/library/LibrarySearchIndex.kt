package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.AlbumSort
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.ArtistSort
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.model.SongSort

/**
 * Library search (LIB-13, audit/data §6). AND of whitespace terms; each term matches as a substring of a field,
 * ignoring case, accents, kana type and punctuation ("acdc" finds "AC/DC"). Songs search title, artist, album,
 * album artist and genre. With a query, results are ranked by relevance (whole field > word start > inside a
 * word, title > artist > album) and the chosen sort only orders equally relevant results. Not thread-safe.
 */
class LibrarySearchIndex(songs: List<Song> = emptyList()) {
    private var songs: List<Song> = songs
    private val sortedCache = HashMap<String, List<Song>>()
    private val folded = HashMap<Long, List<Field>>()

    fun build(songs: List<Song>) {
        this.songs = songs
        sortedCache.clear()
        folded.clear()
    }

    fun getSortedSongs(sort: SongSort, ascending: Boolean): List<Song> =
        sortedCache.getOrPut("${sort.storageName}_$ascending") { LibrarySorter.sortSongs(songs, sort, ascending) }

    /**
     * [order] replaces the plain sort for ordering the matches before the (stable) relevance ranking, for sorts that
     * need outside data such as play counts; it only decides between equally relevant songs.
     */
    fun searchSongs(
        query: String,
        sort: SongSort = SongSort.Title,
        ascending: Boolean = true,
        order: ((List<Song>) -> List<Song>)? = null,
    ): List<Song> {
        val terms = terms(query)
        if (terms.isEmpty()) return getSortedSongs(sort, ascending)
        val scored = HashMap<Long, Int>()
        val matches = songs.filter { s ->
            val fields = folded.getOrPut(s.id) {
                listOf(
                    Field(s.title, 3), Field(s.artist, 2), Field(s.album, 1),
                    Field(s.albumArtist.orEmpty(), 1), Field(s.genre.orEmpty(), 1),
                )
            }
            val score = score(terms, fields)
            if (score > 0) scored[s.id] = score
            score > 0
        }
        return (order?.invoke(matches) ?: LibrarySorter.sortSongs(matches, sort, ascending)).sortedByDescending { scored[it.id] ?: 0 }
    }

    fun searchAlbums(albums: List<AlbumGroup>, query: String, sort: AlbumSort = AlbumSort.Name, ascending: Boolean = true): List<AlbumGroup> {
        val terms = terms(query)
        if (terms.isEmpty()) return LibrarySorter.sortAlbums(albums, sort, ascending)
        val scored = albums.mapNotNull { a ->
            val sc = score(terms, listOf(Field(a.name, 2), Field(a.artist, 1)))
            if (sc > 0) a to sc else null
        }.toMap(HashMap())
        return LibrarySorter.sortAlbums(scored.keys.toList(), sort, ascending).sortedByDescending { scored[it] ?: 0 }
    }

    fun searchArtists(artists: List<ArtistGroup>, query: String, sort: ArtistSort = ArtistSort.Name, ascending: Boolean = true): List<ArtistGroup> {
        val terms = terms(query)
        if (terms.isEmpty()) return LibrarySorter.sortArtists(artists, sort, ascending)
        val scored = artists.mapNotNull { a ->
            val sc = score(terms, listOf(Field(a.name, 1)))
            if (sc > 0) a to sc else null
        }.toMap(HashMap())
        return LibrarySorter.sortArtists(scored.keys.toList(), sort, ascending).sortedByDescending { scored[it] ?: 0 }
    }

    /** A searchable value: folded text, the same text without punctuation/spaces, and its ranking weight. */
    private class Field(raw: String, val weight: Int) {
        val text = normalizeForSearch(raw)
        val squashed = squash(text)
    }

    private class Term(val text: String, val squashed: String)

    /** Whitespace terms of [query]; punctuation-only terms ("love - story") are dropped unless nothing else is left. */
    private fun terms(query: String): List<Term> {
        val all = normalizeForSearch(query.trim()).split(WHITESPACE).filter { it.isNotEmpty() }.map { Term(it, squash(it)) }
        return all.filter { it.squashed.isNotEmpty() }.ifEmpty { all }
    }

    /** 0 = some term matched no field; otherwise the summed best field score of every term. */
    private fun score(terms: List<Term>, fields: List<Field>): Int {
        var total = 0
        for (t in terms) {
            var best = 0
            for (f in fields) {
                val s = termScore(t, f)
                if (s > best) best = s
            }
            if (best == 0) return 0
            total += best
        }
        return total
    }

    private fun termScore(t: Term, f: Field): Int {
        val base = when {
            f.text == t.text -> 8
            f.text.startsWith(t.text) -> 6
            wordStart(f.text, t.text) -> 4
            f.text.contains(t.text) -> 2
            t.squashed.isEmpty() -> return 0
            f.squashed == t.squashed -> 7
            f.squashed.startsWith(t.squashed) -> 5
            f.squashed.contains(t.squashed) -> 1
            else -> return 0
        }
        return base * f.weight
    }

    private fun wordStart(text: String, term: String): Boolean {
        var i = text.indexOf(term)
        while (i >= 0) {
            if (i == 0 || !text[i - 1].isWordChar()) return true
            i = text.indexOf(term, i + 1)
        }
        return false
    }

    companion object {
        private val WHITESPACE = Regex("""\s+""")

        fun filterPlaylists(playlists: List<Playlist>, query: String): List<Playlist> {
            val q = normalizeForSearch(query.trim())
            return if (q.isEmpty()) playlists else playlists.filter { normalizeForSearch(it.name).contains(q) }
        }
    }
}

/**
 * Letters and digits count as word characters. Surrogate halves count too: `Char.isLetterOrDigit()` is false for
 * them, which would drop supplementary-plane letters (e.g. rare CJK such as 𠮷) as if they were punctuation.
 */
private fun Char.isWordChar(): Boolean = isLetterOrDigit() || isSurrogate()

private fun squash(s: String): String = s.filter { it.isWordChar() }
