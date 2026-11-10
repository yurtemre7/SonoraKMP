package de.yurtemre.sonora.shared.playlist

import de.yurtemre.sonora.shared.model.Song

/** M3U export/import helpers (LIB-18, LIB-19, audit/data §4.3–4.4). */
object M3uCodec {
    private val illegal = Regex("""[\\/:*?"<>|]""")
    private val m3uExt = Regex("""\.m3u8?$""")

    /** `#EXTM3U` + per song `#EXTINF:<floor sec>,<artist> - <title>` + path; `\n`-terminated. */
    fun encode(songs: List<Song>): String = buildString {
        append("#EXTM3U\n")
        for (s in songs) {
            append("#EXTINF:").append(s.durationMs / 1000).append(',')
                .append(s.artist).append(" - ").append(s.title).append('\n')
            append(s.filePath).append('\n')
        }
    }

    /** Path lines: trimmed, non-empty, not starting with `#`. */
    fun decodePaths(content: String): List<String> =
        content.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Exact path → else case-insensitive equal (URL-decoded) file name, first in library order. Duplicates kept. */
    fun match(paths: List<String>, library: List<Song>): List<Song> {
        val byPath = HashMap<String, Song>()
        val byName = HashMap<String, Song>()
        for (s in library) {
            byPath.getOrPut(s.filePath) { s }
            byName.getOrPut(fileName(s.filePath).lowercase()) { s }
        }
        return paths.mapNotNull { p ->
            byPath[p] ?: byName[percentDecode(fileName(p)).lowercase()] ?: byName[fileName(p).lowercase()]
        }
    }

    fun sanitizeFileName(name: String): String = illegal.replace(name, "_")

    fun exportFileName(playlistName: String): String = sanitizeFileName(playlistName) + ".m3u"

    fun isM3uFile(fileName: String): Boolean = fileName.lowercase().let { it.endsWith(".m3u") || it.endsWith(".m3u8") }

    /** Playlist name = file name minus `\.m3u8?$`. */
    fun playlistNameFromFile(fileName: String): String = m3uExt.replace(fileName(fileName), "")

    internal fun fileName(path: String): String = path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)

    /** UTF-8 percent decoding; malformed input returned unchanged. */
    internal fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return s
                out += v.toByte(); i += 3
            } else if (c == '%') return s
            else { out.addAll(c.toString().encodeToByteArray().toList()); i++ }
        }
        return out.toByteArray().decodeToString()
    }
}
