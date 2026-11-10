package de.yurtemre.sonora.shared.lyrics

import de.yurtemre.sonora.shared.model.LyricLine
import de.yurtemre.sonora.shared.model.Lyrics

/** LRC / plain lyrics parser (LIB-24, audit/data §8, FIX D-27 decimal fraction, FIX offset). */
object LyricsParser {
    private val stamp = Regex("""\[(\d+):(\d+)(?:\.(\d+))?\]""")
    private val offsetTag = Regex("""^\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("""^\[(ti|ar|al|by|offset|re|ve|length):""", RegexOption.IGNORE_CASE)

    /** Sidecar candidates in lookup order: `<path-no-ext>.lrc`, then `.txt`; empty if no extension. */
    fun sidecarPaths(songPath: String): List<String> {
        val dot = songPath.lastIndexOf('.')
        val sep = maxOf(songPath.lastIndexOf('/'), songPath.lastIndexOf('\\'))
        if (dot <= sep) return emptyList()
        val base = songPath.substring(0, dot)
        return listOf("$base.lrc", "$base.txt")
    }

    /** Parses file content; null when nothing usable remains. */
    fun parse(content: String): Lyrics? {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var offsetMs = 0L
        lines.firstNotNullOfOrNull { offsetTag.find(it) }?.let { offsetMs = it.groupValues[1].toLong() }
        val timed = ArrayList<LyricLine>()
        var anyStamp = false
        for (line in lines) {
            val matches = stamp.findAll(line).toList()
            if (matches.isEmpty()) continue
            anyStamp = true
            val text = stamp.replace(line, "").trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val fracMs = if (frac.isEmpty()) 0L else frac.take(3).padEnd(3, '0').toLong()
                val t = (min * 60_000 + sec * 1000 + fracMs - offsetMs).coerceAtLeast(0)
                timed += LyricLine(t, text)
            }
        }
        if (anyStamp) {
            if (timed.isEmpty()) return null
            return Lyrics(timed.sortedBy { it.timeMs }, isSynchronized = true)
        }
        val plain = lines.filterNot { metaTag.containsMatchIn(it) }.map { LyricLine(0, it) }
        return if (plain.isEmpty()) null else Lyrics(plain, isSynchronized = false)
    }
}
