package de.yurtemre.sonora.shared.scan

import de.yurtemre.sonora.shared.library.ArtistParser
import java.io.File

/**
 * LIB-07 single algorithm: walk from the song dir up to the scan root; per individual artist match
 * `artist.{jpg,png,webp,jpeg}`, then `<artistLower>.{…}`, else dir name == artist → first image.
 * Not thread-safe; use one instance per scan (it caches directory listings and searched artists).
 */
class ArtistImageScanner(rootFolderPath: String?) {
    private val normRoot = rootFolderPath?.trimEnd('/', '\\')?.replace('\\', '/')
    private val dirFiles = HashMap<String, Set<String>>()
    private val searched = HashSet<String>()
    private val result = LinkedHashMap<String, String>()

    /** artist nameLower → absolute image path. */
    val images: Map<String, String> get() = result

    fun addSong(filePath: String, artist: String) {
        val songFile = File(filePath)
        for (name in ArtistParser.parse(artist)) {
            val lower = name.lowercase().trim()
            if (lower.isEmpty() || lower == "unknown artist" || !searched.add(lower)) continue
            resolve(songFile, lower)?.let { result[lower] = it }
        }
    }

    private fun resolve(songFile: File, lowerArtist: String): String? = try {
        var current: File? = songFile.parentFile
        var found: String? = null
        while (current != null && found == null) {
            val norm = current.absolutePath.replace('\\', '/')
            val dir: File = current
            val files = dirFiles.getOrPut(norm) {
                dir.listFiles { f -> f.isFile }?.map { it.name.lowercase() }?.toSet() ?: emptySet()
            }
            if (files.isNotEmpty()) {
                val cands = EXTS.map { "artist.$it" } + EXTS.map { "$lowerArtist.$it" }
                found = cands.firstOrNull { it in files }?.let { File(dir, it).absolutePath }
                if (found == null && dir.name.lowercase().trim() == lowerArtist) {
                    found = dir.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTS }
                        ?.firstOrNull()?.absolutePath
                }
            }
            if (found != null) break
            if (!normRoot.isNullOrEmpty() && norm.equals(normRoot, ignoreCase = true)) break
            val parent = dir.parentFile
            current = if (parent == null || parent.absolutePath == dir.absolutePath) null else parent
        }
        found
    } catch (e: Exception) { null }

    private companion object {
        val EXTS = listOf("jpg", "png", "webp", "jpeg")
        val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")
    }
}
