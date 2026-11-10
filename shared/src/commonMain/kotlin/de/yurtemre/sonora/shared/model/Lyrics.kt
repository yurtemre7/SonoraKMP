package de.yurtemre.sonora.shared.model

/** Parsed lyrics (LIB-24, audit/data lyrics section). */
data class Lyrics(val lines: List<LyricLine>, val isSynchronized: Boolean)

data class LyricLine(val timeMs: Long, val text: String)
