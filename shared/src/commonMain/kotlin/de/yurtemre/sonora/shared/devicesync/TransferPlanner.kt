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

data class TransferPlan(val items: List<PlanItem>, val rejected: List<ManifestFile> = emptyList()) {
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

/** Decides New / Replace / Present per sender file (DSYNC-08, D-72). */
object TransferPlanner {
    fun plan(files: List<ManifestFile>, index: ReceiverIndex): TransferPlan {
        val rejected = ArrayList<ManifestFile>()
        val accepted = ArrayList<Pair<ManifestFile, String>>()
        for (f in files) {
            val path = SyncPaths.sanitize(f.relPath)
            if (path == null || SyncPaths.kindOf(path) != f.kind) rejected += f else accepted += f to path
        }
        val claimed = HashSet<Long>()
        val songByPath = index.library.filter { it.relPath != null }.sortedBy { it.songId }.groupBy { it.relPath!! }
        val results = HashMap<Int, PlanItem>()

        // Pass 1: same path and size.
        for ((f, path) in accepted) {
            if (index.existing[path] != f.size) continue
            results[f.id] = PlanItem(f, Status.Present)
            if (f.kind == FileKind.Audio) songByPath[path]?.firstOrNull { it.songId !in claimed }?.let { claimed += it.songId }
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
                match == null -> PlanItem(f, Status.New, needsFingerprint = f.kind == FileKind.Audio && f.size in sizes)
                match.extension == SyncPaths.extension(path) -> PlanItem(f, Status.Present)
                else -> PlanItem(f, Status.Replace, replaces = match)
            }
            if (match != null) claimed += match.songId
        }
        return TransferPlan(accepted.map { results.getValue(it.first.id) }, rejected)
    }

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
