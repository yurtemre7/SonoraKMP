package de.yurtemre.sonora.shared.repo.api

import de.yurtemre.sonora.shared.model.Song

/**
 * Platform scanner (LIB-03 MediaStore, LIB-05 art cache, LIB-06 directory fallback, LIB-07 artist images).
 * Pure producer: persistence & the unchanged rule (LIB-04) live in [LibraryRepository].
 */
interface LibraryScanner {
    /**
     * Scans [folderPath]/[folderUri] (null = whole device). [cached] supplies existing rows for id
     * allocation (LIB-06) and art reuse. Returns null when the folder is missing/unmounted (LIB-04).
     */
    suspend fun scan(folderPath: String?, folderUri: String?, cached: List<Song>): ScanResult?

    /**
     * Slow, non-blocking part of a scan (cover extraction, SAF sidecar index, lyrics flags, artist
     * images). Run by the repository in the background after the songs are persisted; null = nothing.
     */
    suspend fun enrich(folderPath: String?, folderUri: String?, songs: List<Song>): ScanEnrichment? = null
}

data class ScanEnrichment(
    /** albumId → art path (cached or freshly extracted). */
    val artworkByAlbum: Map<Long, String>,
    /** File paths of songs that have a lyrics sidecar (LIB-24). */
    val lyricsPaths: Set<String>,
    /** LIB-07: artist nameLower → local image path. */
    val artistImages: Map<String, String>,
)

data class ScanResult(
    val songs: List<Song>,
    /** LIB-07: artist nameLower → local image path; null = resolved later by [LibraryScanner.enrich]. */
    val artistImages: Map<String, String>?,
)
