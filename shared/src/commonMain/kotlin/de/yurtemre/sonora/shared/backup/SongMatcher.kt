package de.yurtemre.sonora.shared.backup

import de.yurtemre.sonora.shared.model.Song
import kotlin.math.abs

/** One-to-one backup → library song matcher, 3 passes (BAK-03, audit/data §12.4). */
object SongMatcher {
    const val DURATION_TOLERANCE_MS = 2000L

    /** Returns backup id → library id. */
    fun match(refs: Collection<BackupSongRef>, library: List<Song>): Map<Long, Long> {
        val sortedRefs = refs.sortedBy { it.id }
        val sortedLib = library.sortedBy { it.id }
        val byPath = HashMap<String, Song>()
        val byTags = HashMap<String, MutableList<Song>>()
        val byTail = HashMap<String, MutableList<Song>>()
        for (s in sortedLib) {
            byPath.getOrPut(s.filePath) { s }
            byTags.getOrPut(tagKey(s.title, s.artist, s.album)) { mutableListOf() }.add(s)
            byTail.getOrPut(pathTail(s.filePath)) { mutableListOf() }.add(s)
        }
        val result = LinkedHashMap<Long, Long>()
        val claimed = HashSet<Long>()
        fun pass(find: (BackupSongRef) -> Song?) {
            for (ref in sortedRefs) {
                if (ref.id in result) continue
                val song = find(ref) ?: continue
                if (claimed.add(song.id)) result[ref.id] = song.id
            }
        }
        pass { byPath[it.path] }
        pass { closest(byTags[tagKey(it.title, it.artist, it.album)], it.durationMs, claimed) }
        pass { ref ->
            closest(
                byTail[pathTail(ref.path)]?.filter { norm(it.title) == norm(ref.title) || norm(it.artist) == norm(ref.artist) },
                ref.durationMs, claimed,
            )
        }
        return result
    }

    internal fun norm(v: String) = v.trim().lowercase()
    internal fun tagKey(title: String, artist: String, album: String) = "${norm(title)}\u0000${norm(artist)}\u0000${norm(album)}"
    internal fun pathTail(path: String): String {
        val seg = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        return seg.takeLast(2).joinToString("/").lowercase()
    }

    private fun closest(candidates: List<Song>?, durationMs: Long, claimed: Set<Long>): Song? {
        var best: Song? = null
        var bestDiff = DURATION_TOLERANCE_MS + 1
        for (s in candidates ?: return null) {
            if (s.id in claimed) continue
            val diff = abs(s.durationMs - durationMs)
            if (diff < bestDiff) { best = s; bestDiff = diff }
        }
        return best
    }
}
