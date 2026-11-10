package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song

/** Total-duration parts (LIB-15, audit/data §10.2); null parts are omitted when rendering. */
data class DurationParts(val days: Long?, val hours: Long?, val minutes: Long?, val seconds: Long)

object DurationFormat {
    /** Song `m:ss`, minutes unbounded. */
    fun songDuration(durationMs: Long): String {
        val total = durationMs / 1000
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    /** Seek bar: `h:mm:ss` when ≥ 1 h, else `m:ss`. */
    fun position(durationMs: Long): String {
        val total = durationMs.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = (total % 60).toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
    }

    fun totalParts(songs: List<Song>): DurationParts = totalParts(songs.sumOf { it.durationMs })

    fun totalParts(totalMs: Long): DurationParts {
        val totalSec = totalMs / 1000
        val days = totalSec / 86_400
        val hoursTotal = totalSec / 3600
        val minutesTotal = totalSec / 60
        val h = hoursTotal % 24
        val m = minutesTotal % 60
        return DurationParts(
            days = days.takeIf { it > 0 },
            hours = h.takeIf { it > 0 },
            minutes = m.takeIf { it > 0 || days == 0L || h > 0 }, // never drop minutes under hours (departs from Flutter)
            seconds = totalSec % 60,
        )
    }
}
