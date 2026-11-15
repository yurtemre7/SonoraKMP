package de.yurtemre.sonora.shared.devicesync

import de.yurtemre.sonora.shared.backup.SongMatcher
import kotlin.math.abs

/**
 * A song already on the receiver; [relPath] is null when it lives outside the destination.
 * [extension] is the lowercase extension of the song's actual file.
 */
data class ReceiverSong(
    val songId: Long,
    val relPath: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val size: Long?,
    val extension: String,
)

/** [existing] maps sanitized relPath -> size of files already in the destination. */
data class ReceiverIndex(val existing: Map<String, Long>, val library: List<ReceiverSong>)

enum class Status { New, Replace, Present }

data class PlanItem(
    val file: ManifestFile,
    val status: Status,
    val replaces: ReceiverSong? = null,
    val needsFingerprint: Boolean = false,
)

data class AudioFormatStat(val count: Int, val bytes: Long)

/**
 * [extras] are songs inside the destination that the sender does not have (no path or tag match): the optional
 * "mirror the sender" step offers to delete them after the transfer.
 */
data class TransferPlan(
    val items: List<PlanItem>,
    val rejected: List<ManifestFile> = emptyList(),
    val extras: List<ReceiverSong> = emptyList(),
) {
    private val byId = items.associateBy { it.file.id }

    fun defaultSelection(): Set<Int> =
        items.filter { it.status != Status.Present }.mapTo(LinkedHashSet()) { it.file.id }

    private fun selected(ids: Set<Int>) = ids.mapNotNull { byId[it] }.filter { it.status != Status.Present }

    fun bytesNeeded(selectedIds: Set<Int>): Long = selected(selectedIds).sumOf { it.file.size }

    fun bytesFreedAfter(selectedIds: Set<Int>): Long =
        selected(selectedIds).filter { it.status == Status.Replace }.sumOf { it.replaces?.size ?: 0L }

    /** Needed bytes + [SAFETY_MARGIN_BYTES] within [freeBytes]; a selection that writes nothing always fits. */
    fun fits(selectedIds: Set<Int>, freeBytes: Long): Boolean {
        val items = selected(selectedIds)
        return items.isEmpty() || items.sumOf { it.file.size } + SAFETY_MARGIN_BYTES <= freeBytes
    }

    fun groupsByKind(): Map<FileKind, List<PlanItem>> = items.groupBy { it.file.kind }

    /** Extension -> count and total bytes over all audio items, whatever their status. */
    fun audioFormats(): Map<String, AudioFormatStat> {
        val out = LinkedHashMap<String, AudioFormatStat>()
        for (item in items) {
            if (item.file.kind != FileKind.Audio) continue
            val ext = SyncPaths.extension(item.file.relPath)
            val cur = out[ext] ?: AudioFormatStat(0, 0L)
            out[ext] = AudioFormatStat(cur.count + 1, cur.bytes + item.file.size)
        }
        return out
    }

    companion object {
        const val SAFETY_MARGIN_BYTES = 209_715_200L
    }
}

/**
 * Decides New / Replace / Present per sender file (DSYNC-08, D-72).
 *
 * - Paths are compared ignoring case: the destination (FUSE or SAF) is case-insensitive, so `artist/cover.jpg`
 *   from the sender is the receiver's `Artist/cover.jpg`.
 * - A New file of any kind is fingerprinted when a file of the same size exists in the destination.
 * - A lyrics or cover file whose audio files (for lyrics the one with the same base name, for covers all in its
 *   folder) are all Present by tags in *another* folder (or outside the destination) is Present as well: the
 *   receiver keeps those songs elsewhere, so a copy would be an orphan next to no audio.
 */
object TransferPlanner {
    fun plan(files: List<ManifestFile>, index: ReceiverIndex): TransferPlan {
        val rejected = ArrayList<ManifestFile>()
        val accepted = ArrayList<Pair<ManifestFile, String>>()
        for (f in files) {
            val path = SyncPaths.sanitize(f.relPath)
            if (path == null || SyncPaths.kindOf(path) != f.kind) rejected += f else accepted += f to path
        }
        val claimed = HashSet<Long>()
        val songByPath = index.library.filter { it.relPath != null }.sortedBy { it.songId }.groupBy { it.relPath!!.lowercase() }
        val sizesByPath = HashMap<String, MutableSet<Long>>()
        for ((p, size) in index.existing) sizesByPath.getOrPut(p.lowercase()) { HashSet() } += size
        val results = HashMap<Int, PlanItem>()
        /** Audio items Present by tags: the receiver song's folder (null = outside the destination). */
        val presentElsewhere = HashMap<Int, String?>()

        // Pass 1: same path (ignoring case) and size.
        for ((f, path) in accepted) {
            if (sizesByPath[path.lowercase()]?.contains(f.size) != true) continue
            results[f.id] = PlanItem(f, Status.Present)
            if (f.kind == FileKind.Audio) {
                songByPath[path.lowercase()]?.firstOrNull { it.songId !in claimed }?.let { claimed += it.songId }
            }
        }

        // Pass 2: tag matching for the remaining audio, lowest sender id first.
        val byTags = index.library.sortedBy { it.songId }
            .groupBy { SongMatcher.tagKey(it.title, it.artist, it.album) }
        val sizes = index.existing.values.toSet()
        for ((f, path) in accepted.sortedBy { it.first.id }) {
            if (f.id in results) continue
            val meta = f.audio.takeIf { f.kind == FileKind.Audio }
            val match = meta?.let { closest(byTags[SongMatcher.tagKey(it.title, it.artist, it.album)], it.durationMs, claimed) }
            results[f.id] = when {
                match == null -> PlanItem(f, Status.New, needsFingerprint = f.size in sizes)
                match.extension == SyncPaths.extension(path) -> {
                    presentElsewhere[f.id] = match.relPath?.let(SyncPaths::parent)
                    PlanItem(f, Status.Present)
                }
                else -> PlanItem(f, Status.Replace, replaces = match)
            }
            if (match != null) claimed += match.songId
        }

        // Pass 3: sidecars of songs the receiver keeps in another folder.
        val audioByDir = accepted.filter { it.first.kind == FileKind.Audio }.groupBy { SyncPaths.parent(it.second).lowercase() }
        for ((f, path) in accepted) {
            if (f.kind == FileKind.Audio || results.getValue(f.id).status == Status.Present) continue
            val dir = SyncPaths.parent(path)
            val inDir = audioByDir[dir.lowercase()].orEmpty()
            val owners = if (f.kind == FileKind.Lyrics) {
                inDir.filter { stem(it.second).equals(stem(path), ignoreCase = true) }
            } else {
                inDir
            }
            val elsewhere = owners.isNotEmpty() && owners.all { (audio, _) ->
                audio.id in presentElsewhere && presentElsewhere[audio.id]?.equals(dir, ignoreCase = true) != true
            }
            if (elsewhere) results[f.id] = PlanItem(f, Status.Present)
        }
        // Songs inside the destination that no sender file claimed. Never offered when the sender sends no audio at
        // all (an empty or broken manifest must not wipe the receiver), nor for a path the sender is about to write.
        val senderPaths = accepted.mapTo(HashSet()) { it.second.lowercase() }
        val extras = if (accepted.none { it.first.kind == FileKind.Audio }) emptyList() else {
            index.library
                .filter { it.relPath != null && it.songId !in claimed && it.relPath.lowercase() !in senderPaths }
                .sortedBy { it.songId }
        }
        return TransferPlan(accepted.map { results.getValue(it.first.id) }, rejected, extras)
    }

    private fun stem(path: String) = SyncPaths.baseName(path).substringBeforeLast('.')

    private fun closest(candidates: List<ReceiverSong>?, durationMs: Long, claimed: Set<Long>): ReceiverSong? {
        var best: ReceiverSong? = null
        var bestDiff = SongMatcher.DURATION_TOLERANCE_MS + 1
        for (s in candidates ?: return null) {
            if (s.songId in claimed) continue
            val diff = abs(s.durationMs - durationMs)
            if (diff < bestDiff) { best = s; bestDiff = diff }
        }
        return best
    }
}
