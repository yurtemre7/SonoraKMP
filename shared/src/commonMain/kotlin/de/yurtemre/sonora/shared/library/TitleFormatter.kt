package de.yurtemre.sonora.shared.library

/** `Song.displayTitle` (LIB-14, audit/data §10.1). */
object TitleFormatter {
    private val featRegex = Regex("""\s*[(\[](?:feat|featuring|ft)\.?\s+[^\])]+[)\]]""", RegexOption.IGNORE_CASE)
    private val videoTagRegex = Regex(
        """\s*[(\[](?:official\s+(?:music\s+video|lyric\s+video|video|audio|visualizer)|music\s+video|lyric\s+video)[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    fun displayTitle(
        title: String,
        artist: String,
        filterFeatures: Boolean,
        filterArtist: Boolean,
        filterVideoTags: Boolean = false,
    ): String {
        if (!filterFeatures && !filterArtist && !filterVideoTags) return title.trim()
        var cleaned = title
        if (filterFeatures) cleaned = cleaned.replace(featRegex, "")
        if (filterVideoTags) cleaned = cleaned.replace(videoTagRegex, "")
        if (!filterArtist || artist.isEmpty()) return cleaned.trim().ifEmpty { title }
        val esc = Regex.escape(artist)
        var result = cleaned.replace(Regex("""\s*[—–~-]\s*$esc\s*$""", RegexOption.IGNORE_CASE), "")
        if (result == cleaned) result = cleaned.replace(Regex("""^\s*$esc\s*[—–~-]\s*""", RegexOption.IGNORE_CASE), "")
        return result.trim().ifEmpty { cleaned.trim() }
    }
}
