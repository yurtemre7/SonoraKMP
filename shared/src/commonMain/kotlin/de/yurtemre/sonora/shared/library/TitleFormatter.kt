package de.yurtemre.sonora.shared.library

/** `Song.displayTitle` (LIB-14, audit/data §10.1). */
object TitleFormatter {
    private val featRegex = Regex("""\s*[(\[](?:feat|featuring|ft)\.?\s+[^\])]+[)\]]""", RegexOption.IGNORE_CASE)

    fun displayTitle(title: String, artist: String, filterFeatures: Boolean, filterArtist: Boolean): String {
        if (!filterFeatures && !filterArtist) return title.trim()
        var cleaned = title
        if (filterFeatures) cleaned = cleaned.replace(featRegex, "")
        if (!filterArtist || artist.isEmpty()) return cleaned.trim().ifEmpty { title }
        val esc = Regex.escape(artist)
        var result = cleaned.replace(Regex("""\s*[—–-]\s*$esc\s*$"""), "")
        if (result == cleaned) result = cleaned.replace(Regex("""^\s*$esc\s*[—–-]\s*"""), "")
        return result.trim().ifEmpty { cleaned.trim() }
    }
}
