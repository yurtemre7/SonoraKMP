package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.Song

/** Album grouping (LIB-10, audit/data §5.2). */
object AlbumGrouper {
    const val UNKNOWN_ALBUM = "Unknown Album"
    private const val UNKNOWN_KEY = "unknown album|||unknown artist"

    fun build(songs: List<Song>): List<AlbumGroup> {
        val buckets = LinkedHashMap<String, MutableList<Song>>()
        for (song in songs) {
            val primary = ArtistParser.parse(song.artist).first()
            val albumName = song.album.trim().ifEmpty { UNKNOWN_ALBUM }
            val key = if (primary == ArtistParser.UNKNOWN_ARTIST || albumName.lowercase() == "unknown album") {
                UNKNOWN_KEY
            } else {
                albumName.lowercase()
            }
            buckets.getOrPut(key) { ArrayList() }.add(song)
        }
        val groups = ArrayList<AlbumGroup>()
        for ((key, bucket) in buckets) {
            if (key == UNKNOWN_KEY) {
                groups += AlbumGroup(UNKNOWN_ALBUM, ArtistParser.parse(bucket.first().artist).first(), sortTracks(bucket))
            } else {
                groups += groupBucket(bucket)
            }
        }
        return groups.sortedBy { it.name.lowercase() }
    }

    private fun groupBucket(bucket: List<Song>): List<AlbumGroup> {
        val name = bucket.first().album.trim()
        val counts = LinkedHashMap<String, Int>()
        val cased = HashMap<String, String>()
        var common: LinkedHashSet<String>? = null
        for (song in bucket) {
            val parsed = ArtistParser.parse(song.artist)
            val primaryLower = parsed.first().lowercase()
            counts[primaryLower] = (counts[primaryLower] ?: 0) + 1
            cased[primaryLower] = parsed.first()
            val lowerSet = parsed.mapTo(LinkedHashSet()) { it.lowercase() }
            common = common?.apply { retainAll(lowerSet) } ?: lowerSet
        }
        val firstCommon = common?.firstOrNull()
        if (firstCommon != null) {
            val display = ArtistParser.parse(bucket.first().artist).firstOrNull { it.lowercase() == firstCommon } ?: firstCommon
            return listOf(AlbumGroup(name, display, sortTracks(bucket)))
        }
        var dominant: String? = null
        var max = 0
        for ((k, c) in counts) if (c > max) { max = c; dominant = k }
        if (dominant != null && max > bucket.size / 2.0) {
            return listOf(AlbumGroup(name, cased.getValue(dominant), sortTracks(bucket)))
        }
        if (counts.size == 1) return listOf(AlbumGroup(name, cased.values.first(), sortTracks(bucket)))
        val split = LinkedHashMap<String, MutableList<Song>>()
        for (song in bucket) {
            split.getOrPut(ArtistParser.parse(song.artist).first().lowercase()) { ArrayList() }.add(song)
        }
        return split.values.map { AlbumGroup(name, ArtistParser.parse(it.first().artist).first(), sortTracks(it)) }
    }

    fun sortTracks(songs: List<Song>): List<Song> = songs.sortedWith { a, b ->
        val disc = (a.discNumber ?: 1).compareTo(b.discNumber ?: 1)
        if (disc != 0) return@sortedWith disc
        val ta = a.trackNumber ?: 0
        val tb = b.trackNumber ?: 0
        val track = ta.compareTo(tb)
        if (track != 0) return@sortedWith track
        if (ta == 0 && tb == 0) a.filePath.compareTo(b.filePath) else a.titleLower.compareTo(b.titleLower)
    }

    /** `buildAlbumGroup(name, artist, songs)` lookup (audit/data §5.3). */
    fun find(name: String, artist: String, songs: List<Song>): AlbumGroup {
        val albums = build(songs)
        val nameLower = name.trim().lowercase()
        val artistLower = artist.trim().lowercase()
        return albums.firstOrNull { album ->
            album.name.lowercase() == nameLower && (album.artist.lowercase() == artistLower ||
                ArtistParser.parse(album.artist).any { it.lowercase() == artistLower })
        } ?: albums.firstOrNull { it.name.lowercase() == nameLower } ?: AlbumGroup(name, artist, emptyList())
    }
}
