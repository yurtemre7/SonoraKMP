package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot

data class JumpBackItem(val albumName: String, val albumArtist: String, val coverSong: Song, val songs: List<Song>)

object JumpBackIn {
    /** Most recently played distinct albums, newest first; blank album names are skipped. */
    fun recentAlbums(songs: List<Song>, stats: StatsSnapshot, limit: Int = 3): List<JumpBackItem> {
        if (limit <= 0) return emptyList()
        val buckets = LinkedHashMap<Pair<String, String>, MutableList<Song>>()
        for (s in songs.distinctBy { it.id }) {
            val name = s.album.trim()
            if (name.isEmpty()) continue
            buckets.getOrPut(name.lowercase() to AlbumGrouper.albumArtistOf(s).lowercase()) { ArrayList() }.add(s)
        }
        return buckets.values
            .mapNotNull { group ->
                val last = group.mapNotNull { stats.songs[it.id]?.lastPlayedAtMs }.maxOrNull() ?: return@mapNotNull null
                last to group
            }
            .sortedByDescending { it.first }
            .take(limit)
            .map { (_, group) ->
                val sorted = AlbumGrouper.sortTracks(group)
                val first = sorted.first()
                JumpBackItem(first.album.trim(), AlbumGrouper.albumArtistOf(first), first, sorted)
            }
    }
}
