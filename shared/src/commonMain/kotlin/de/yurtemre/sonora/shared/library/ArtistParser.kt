package de.yurtemre.sonora.shared.library

/** Individual-artist parsing (LIB-09, audit/data §5.1). */
object ArtistParser {
    const val UNKNOWN_ARTIST = "Unknown Artist"

    private val splitRegex = Regex(
        """[,;/&\\]|\s+(?:feat\.?|ft\.?|featuring|vs\.?|with|[xX+])\s+""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(artist: String): List<String> {
        val trimmed = artist.trim()
        if (trimmed.isEmpty()) return listOf(UNKNOWN_ARTIST)
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        for (raw in trimmed.split(splitRegex)) {
            val part = raw.trim().trimStart('(', '[', ' ').trimEnd(')', ']', ' ').trim()
            if (part.isEmpty()) continue
            if (seen.add(part.lowercase())) result.add(part)
        }
        return result.ifEmpty { listOf(UNKNOWN_ARTIST) }
    }
}
