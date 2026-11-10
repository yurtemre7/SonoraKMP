package de.yurtemre.sonora.shared.scan

import android.content.Context
import android.net.Uri
import de.yurtemre.sonora.shared.model.Song
import java.io.File

/** Result of a directory scan (LIB-06). [songs] keep cached entries for unchanged files. */
data class DirectoryScanResult(val songs: List<Song>, val reparsedCount: Int)

/**
 * LIB-06 directory scan fallback (audit/data §2.4). Walks the persisted SAF tree (DocumentsContract child
 * queries) when [treeUri] is given, else / additionally `java.io.File`. Blocking; call on IO. Never throws.
 */
class DirectoryScanner(private val context: Context) {
    private val artDir: File get() = File(context.filesDir, "embedded_art").apply { mkdirs() }

    fun embeddedArtFile(songId: Long) = File(artDir, "$songId.jpg")

    private class Listing {
        val audio = LinkedHashMap<String, TreeFile?>() // path → SAF entry (null when found via File)
        val stats = HashMap<String, Pair<Long, Long>>() // path → (mtime, size)
        val covers = HashMap<String, String>() // parent dir → cover path
    }

    /**
     * @param existing cached songs (ids/favorites carried by path; unchanged files kept as-is).
     * @param minNextId lower bound for new ids — pass `max(all known ids incl. MediaStore) + 1` so new ids
     *   never collide with MediaStore `_ID`s (audit §2.6).
     * @return null when the folder is unreachable (cache must stay untouched).
     */
    fun scan(rootPath: String, treeUri: Uri?, existing: List<Song>, minNextId: Long = 1): DirectoryScanResult? {
        val root = rootPath.trimEnd('/')
        val listing = Listing()
        if (treeUri != null) {
            TreeWalker.walk(context.contentResolver, treeUri) { f -> classify(listing, f.path, f, f.lastModifiedMs, f.size) }
        }
        if (listing.audio.isEmpty()) {
            val dir = File(root)
            if (!dir.isDirectory && treeUri == null) return null
            walkFiles(dir, listing)
        }
        if (listing.audio.isEmpty() && !File(root).isDirectory && treeUri == null) return null

        val byPath = existing.associateBy { it.filePath }
        var nextId = maxOf(minNextId, (existing.maxOfOrNull { it.id } ?: 0L) + 1)
        val out = ArrayList<Song>(listing.audio.size)
        var reparsed = 0
        for ((path, saf) in listing.audio) {
            val (mtime, size) = listing.stats[path] ?: (0L to 0L)
            val cached = byPath[path]
            if (cached != null && cached.lastModifiedMs == mtime && cached.fileSize == size &&
                cached.artist != "Local Audio" && cached.album != "Synced Folder" && cached.format != null
            ) { out += cached; continue }
            val id = cached?.id ?: nextId++
            out += parse(path, saf?.uri, id, root, listing.covers, mtime, size).copy(isFavorite = cached?.isFavorite ?: false,
                favoriteDateMs = cached?.favoriteDateMs, seedColor = null)
            reparsed++
        }
        pruneArt(out.mapTo(HashSet()) { it.id })
        return DirectoryScanResult(out, reparsed)
    }

    private fun classify(l: Listing, path: String, saf: TreeFile?, mtime: Long, size: Long) {
        val name = path.substringAfterLast('/').lowercase()
        val parent = path.substringBeforeLast('/', "")
        if (name in COVER_NAMES) { l.covers[parent] = path; return }
        val ext = name.substringAfterLast('.', "")
        if (ext in AUDIO_EXTS) { l.audio[path] = saf; l.stats[path] = mtime to size }
    }

    private fun walkFiles(dir: File, l: Listing) {
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val kids = try { d.listFiles() } catch (e: Exception) { null } ?: continue
            for (f in kids) {
                try {
                    if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                    if (f.isDirectory) stack.add(f) else classify(l, f.absolutePath, null, f.lastModified(), f.length())
                } catch (e: Exception) {}
            }
        }
    }

    private fun parse(path: String, uri: Uri?, id: Long, root: String, covers: Map<String, String>, mtime: Long, size: Long): Song {
        val file = File(path)
        var temp: File? = null
        val tags = try {
            val src = if (file.canRead() || uri == null) file else copyToTemp(uri, file.extension).also { temp = it } ?: file
            TagReader.read(src, withPicture = true)
        } catch (e: Exception) { AudioTags() } finally { temp?.delete() }

        val name = file.name
        val parentDir = path.substringBeforeLast('/', "")
        val parentName = parentDir.substringAfterLast('/')
        val grandDir = parentDir.substringBeforeLast('/', "")
        val grandName = grandDir.substringAfterLast('/')

        val title = tags.title ?: name.substringBeforeLast('.')
        var artist = (tags.artist ?: tags.albumArtist)?.trim()
        if (artist.isNullOrEmpty() || artist == "Unknown Artist") {
            artist = if (grandDir.startsWith(root) && grandName.isNotEmpty() && grandName != "music" && grandName != "Download")
                grandName else "Unknown Artist"
        }
        val album = tags.album?.takeIf { it.isNotEmpty() && it != "Unknown Album" } ?: parentName.ifEmpty { "Unknown Album" }
        val track = tags.trackNumber ?: TRACK_RE.find(name)?.groupValues?.get(1)?.toIntOrNull()

        val art = tags.picture?.let { bytes ->
            try { embeddedArtFile(id).apply { writeBytes(bytes) }.absolutePath } catch (e: Exception) { null }
        } ?: run { embeddedArtFile(id).delete(); covers[parentDir] ?: covers[grandDir] }

        return Song(
            id = id, title = title, artist = artist, album = album, durationMs = tags.durationMs ?: 0L,
            filePath = path, artworkPath = art, format = tags.format, bitrate = tags.bitrate,
            sampleRate = tags.sampleRate, lastModifiedMs = mtime, fileSize = size,
            hasLyrics = LyricsLocator.hasLyrics(path), trackNumber = track, discNumber = tags.discNumber,
            genre = tags.genre, year = tags.year,
        )
    }

    private fun copyToTemp(uri: Uri, ext: String): File? = try {
        val t = File.createTempFile("dirscan_", if (ext.isEmpty()) null else ".$ext", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { i -> t.outputStream().use { i.copyTo(it) } } ?: run { t.delete(); return null }
        t
    } catch (e: Exception) { null }

    /** FIX D-21: delete embedded art of songs no longer in the library. */
    private fun pruneArt(keep: Set<Long>) {
        try {
            artDir.listFiles()?.forEach { f ->
                val id = f.name.removeSuffix(".jpg").toLongOrNull()
                if (id == null || id !in keep) f.delete()
            }
        } catch (e: Exception) {}
    }

    companion object {
        val AUDIO_EXTS = setOf("mp3", "m4a", "mp4", "aac", "flac", "ogg", "opus", "wav", "wma", "amr", "3gp", "ts", "mkv", "mid", "midi")
        val COVER_NAMES = setOf("cover.jpg", "cover.png", "cover.webp")
        private val TRACK_RE = Regex("""^(\d+)\s*[-_.]?\s*""")
    }
}
