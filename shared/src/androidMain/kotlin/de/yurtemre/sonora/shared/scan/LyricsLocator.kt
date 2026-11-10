package de.yurtemre.sonora.shared.scan

import de.yurtemre.sonora.shared.lyrics.LyricsParser
import de.yurtemre.sonora.shared.model.Lyrics
import android.content.ContentResolver
import java.io.File

/**
 * LIB-24 sidecar lookup: `<path-no-ext>.lrc` else `.txt` (exact case). Uses java.io.File — on API 30+
 * non-media sidecars are only visible with direct file access to the folder (scoped storage); missing
 * access simply yields "no lyrics".
 */
object LyricsLocator {
    fun find(songPath: String): File? = try {
        LyricsParser.sidecarPaths(songPath).map(::File).firstOrNull { it.isFile }
    } catch (e: Exception) { null }

    fun hasLyrics(songPath: String): Boolean = find(songPath) != null

    fun readText(songPath: String): String? = try { find(songPath)?.readText() } catch (e: Exception) { null }

    fun load(songPath: String): Lyrics? = readText(songPath)?.let { runCatching { LyricsParser.parse(it) }.getOrNull() }

    // SAF fallback (API 30+): File lookup first, then the tree index built from the onboarding tree URI.
    fun hasLyrics(songPath: String, index: TreeSidecarIndex?): Boolean =
        hasLyrics(songPath) || index?.lyricsUriFor(songPath) != null

    fun readText(songPath: String, resolver: ContentResolver, index: TreeSidecarIndex?): String? =
        readText(songPath) ?: try {
            index?.lyricsUriFor(songPath)?.let { u -> resolver.openInputStream(u)?.use { it.readBytes().decodeToString() } }
        } catch (e: Exception) { null }

    fun load(songPath: String, resolver: ContentResolver, index: TreeSidecarIndex?): Lyrics? =
        readText(songPath, resolver, index)?.let { runCatching { LyricsParser.parse(it) }.getOrNull() }
}
