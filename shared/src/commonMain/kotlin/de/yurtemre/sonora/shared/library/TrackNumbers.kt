package de.yurtemre.sonora.shared.library

/** MediaStore `TRACK` decoding: values ≥ 1000 encode `disc * 1000 + track`. */
object TrackNumbers {
    /** Returns (disc, track); null/≤ 0 parts are null. */
    fun split(raw: Int?): Pair<Int?, Int?> {
        if (raw == null || raw <= 0) return null to null
        if (raw < 1000) return null to raw
        return (raw / 1000) to (raw % 1000).takeIf { it > 0 }
    }
}
