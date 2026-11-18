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

    /** [enrich] with per-item progress reports (stage, done, total); default ignores [onProgress]. */
    suspend fun enrich(
        folderPath: String?,
        folderUri: String?,
        songs: List<Song>,
        onProgress: (BackgroundWork.Stage, Int, Int) -> Unit,
    ): ScanEnrichment? = enrich(folderPath, folderUri, songs)

    /**
     * [enrich] that hands every result over as soon as it is ready (covers in batches, then lyrics, then artist
     * images) so the repository can persist and show each one without waiting for the slowest. Default: one
     * [enrich] call, parts delivered at the end.
     */
    suspend fun enrichIncremental(
        folderPath: String?,
        folderUri: String?,
        songs: List<Song>,
        onProgress: (BackgroundWork.Stage, Int, Int) -> Unit,
        onPart: suspend (EnrichmentPart) -> Unit,
    ) {
        val e = enrich(folderPath, folderUri, songs, onProgress) ?: return
        onPart(EnrichmentPart.Artwork(e.artworkByAlbum))
        onPart(EnrichmentPart.Lyrics(e.lyricsPaths))
        onPart(EnrichmentPart.ArtistImages(e.artistImages))
    }
}

/** One finished piece of [LibraryScanner.enrichIncremental]. Artwork parts are additive and may repeat. */
sealed interface EnrichmentPart {
    /** albumId → art path for albums whose cover was found since the last part. */
    data class Artwork(val byAlbum: Map<Long, String>) : EnrichmentPart
    /** The cover pass is over (finished or out of time); no more [Artwork] batches from the extraction queue. */
    data object ArtworkFinished : EnrichmentPart
    /** Complete set of file paths that have lyrics; every other scanned song has none. */
    data class Lyrics(val withLyrics: Set<String>) : EnrichmentPart
    /** Complete artist nameLower → image path map. */
    data class ArtistImages(val images: Map<String, String>) : EnrichmentPart
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
