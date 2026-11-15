package de.yurtemre.sonora.shared.devicesync

/**
 * Path safety and file classification for device sync. Every relative path received over the network
 * must pass [sanitize] before it touches the file system.
 */
object SyncPaths {
    /** Mirrors `DirectoryScanner.AUDIO_EXTS` (androidMain). */
    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "mp3", "m4a", "mp4", "aac", "flac", "ogg", "opus", "wav", "wma", "amr", "3gp", "ts", "mkv", "mid", "midi",
    )
    val LYRICS_EXTENSIONS: Set<String> = setOf("lrc", "txt")
    val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp")

    /**
     * Lowercase union of `AndroidLibraryScanner.FOLDER_COVER_NAMES` and `DirectoryScanner.COVER_NAMES`
     * (both androidMain). Copied here so commonMain does not depend on androidMain; keep in sync.
     */
    val COVER_FILE_NAMES: Set<String> = setOf("cover.jpg", "cover.png", "cover.webp", "folder.jpg")

    const val MAX_SEGMENT_BYTES = 255
    const val MAX_DEPTH = 16
    const val MAX_TOTAL_LENGTH = 1024

    private const val RESERVED_CHARS = "<>:\"|?*"

    /** Returns the normalized relative path (joined with `/`) or null when it is unsafe. */
    fun sanitize(relPath: String): String? {
        if (relPath.isEmpty() || relPath.length > MAX_TOTAL_LENGTH) return null
        val segments = relPath.replace('\\', '/').split('/')
        if (segments.size > MAX_DEPTH) return null
        val out = ArrayList<String>(segments.size)
        for (raw in segments) {
            if (raw.isEmpty() || raw == "." || raw == "..") return null
            if (raw.any { it.code < 0x20 || it.code == 0x7F || it in RESERVED_CHARS }) return null
            val seg = raw.trimEnd('.', ' ')
            if (seg.isEmpty()) return null
            if (seg.encodeToByteArray().size > MAX_SEGMENT_BYTES) return null
            out += seg
        }
        val result = out.joinToString("/")
        return if (result.length > MAX_TOTAL_LENGTH) null else result
    }

    /** Classifies by extension (and cover file name); null when the type is not allowed. */
    fun kindOf(relPath: String): FileKind? {
        val ext = extension(relPath)
        return when {
            ext in AUDIO_EXTENSIONS -> FileKind.Audio
            ext in LYRICS_EXTENSIONS -> FileKind.Lyrics
            ext in IMAGE_EXTENSIONS && baseName(relPath).lowercase() in COVER_FILE_NAMES -> FileKind.Cover
            else -> null
        }
    }

    /** Lowercase extension without the dot, or "" when the file name has none. */
    fun extension(relPath: String): String {
        val name = baseName(relPath)
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
    }

    /** The last path segment. */
    fun baseName(relPath: String): String = relPath.substringAfterLast('/')

    /** Everything before the last segment, or "" for a top-level file. */
    fun parent(relPath: String): String =
        if ('/' in relPath) relPath.substringBeforeLast('/') else ""

    /** `Song.mp3` -> `Song (2).mp3` for [attempt] 2, `(3)` for 3, ...; attempts below 2 return the path unchanged. */
    fun collisionName(relPath: String, attempt: Int): String {
        if (attempt < 2) return relPath
        val name = baseName(relPath)
        val dot = name.lastIndexOf('.')
        val renamed = if (dot <= 0) "$name ($attempt)" else "${name.substring(0, dot)} ($attempt)${name.substring(dot)}"
        val parent = parent(relPath)
        return if (parent.isEmpty()) renamed else "$parent/$renamed"
    }
}
