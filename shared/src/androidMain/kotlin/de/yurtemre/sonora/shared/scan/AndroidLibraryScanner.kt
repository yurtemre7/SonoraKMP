package de.yurtemre.sonora.shared.scan

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import de.yurtemre.sonora.shared.library.TrackNumbers
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.platform.TreeUriPaths
import de.yurtemre.sonora.shared.repo.api.BackgroundWork
import de.yurtemre.sonora.shared.repo.api.LibraryScanner
import de.yurtemre.sonora.shared.repo.api.ScanEnrichment
import de.yurtemre.sonora.shared.repo.api.ScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * P3-12 Android [LibraryScanner] (LIB-03..07, LIB-24; audit/data §2.2–2.7).
 *
 * MediaStore first; directory scan when MediaStore is empty or fails for the folder. One
 * [TreeSidecarIndex] per scan (when a tree URI is persisted) feeds `hasLyrics` for every song and
 * SAF-only artist images. Returns null (= repository keeps the cache) on missing folder or any failure.
 * The unchanged rule (LIB-04) is applied by the repository; this class only produces its inputs
 * (`filePath`, `lastModifiedMs`, `fileSize`, `artworkPath`).
 */
class AndroidLibraryScanner(
    private val context: Context,
    private val resolver: ContentResolver = context.contentResolver,
    private val filesDir: File = context.filesDir,
) : LibraryScanner {

    private val mediaStore = MediaStoreScanner(resolver)
    private val artworkCache = ArtworkCache(context)
    private val directoryScanner = DirectoryScanner(context)
    private val artistImageDir: File get() = File(filesDir, "artist_images").apply { mkdirs() }

    private val _backgroundArt = MutableSharedFlow<Map<Long, String>>(extraBufferCapacity = 8)

    /** LIB-05 warm mode (FIX D-20): albumId → art path found after [scan] returned; repository patches rows. */
    val backgroundArt: SharedFlow<Map<Long, String>> = _backgroundArt.asSharedFlow()

    override suspend fun scan(folderPath: String?, folderUri: String?, cached: List<Song>): ScanResult? =
        withContext(Dispatchers.IO) {
            try {
                scanInternal(folderPath?.takeIf { it.isNotBlank() }, folderUri?.takeIf { it.isNotBlank() }, cached)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
        }

    private suspend fun scanInternal(folderPath: String?, folderUri: String?, cached: List<Song>): ScanResult? {
        val treeUri: Uri? = folderUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val root = folderPath ?: treeUri?.let { TreeUriPaths.treeUriToPath(it) }
        // Missing/unmounted folder → keep cache (LIB-04). A readable SAF tree counts as present.
        if (root != null && !File(root).isDirectory && treeUri == null) return null

        // Blocking path = MediaStore query + row mapping only; the SAF index, lyrics, covers and artist
        // images are resolved in [enrich] after the songs are persisted.
        val rows = mediaStore.query(root)

        if (!rows.isNullOrEmpty()) return fromMediaStore(rows, cached)

        if (root == null) {
            // No folder and MediaStore empty: keep cached songs whose file still exists (audit §2.2 step 4).
            return ScanResult(cached.filter { File(it.filePath).exists() }, null)
        }

        val minNextId = maxOf(
            rows.orEmpty().maxOfOrNull { it.id } ?: 0L,
            cached.maxOfOrNull { it.id } ?: 0L,
        ) + 1
        val dir = directoryScanner.scan(root, treeUri, cached, minNextId) ?: return null
        return ScanResult(dir.songs, null)
    }

    /** Background half of a scan (see [LibraryScanner.enrich]): no per-song work blocks the UI result. */
    override suspend fun enrich(folderPath: String?, folderUri: String?, songs: List<Song>): ScanEnrichment? =
        enrich(folderPath, folderUri, songs) { _, _, _ -> }

    override suspend fun enrich(
        folderPath: String?,
        folderUri: String?,
        songs: List<Song>,
        onProgress: (BackgroundWork.Stage, Int, Int) -> Unit,
    ): ScanEnrichment? =
        withContext(Dispatchers.IO) {
            val treeUri: Uri? = folderUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val root = folderPath?.takeIf { it.isNotBlank() } ?: treeUri?.let { TreeUriPaths.treeUriToPath(it) }
            val index = TreeSidecarIndex.build(resolver, treeUri)
            val albumSongs = songs.filter { (it.albumId ?: 0) > 0 }.groupBy { it.albumId!! }
            val art = if (albumSongs.isEmpty()) emptyMap() else {
                val sources = albumSongs.mapValues { (_, s) -> s.first().let { ArtworkCache.Source(it.filePath, it.id) } }
                artworkCache.extractAll(albumSongs.keys, sources) { d, t -> onProgress(BackgroundWork.Stage.Artwork, d, t) }.also { runCatching { artworkCache.prune(albumSongs.keys) } }
            }
            val lyrics = HashSet<String>()
            songs.forEachIndexed { i, s ->
                if (LyricsLocator.hasLyrics(s.filePath, index)) lyrics += s.filePath
                onProgress(BackgroundWork.Stage.Lyrics, i + 1, songs.size)
            }
            ScanEnrichment(art, lyrics, artistImages(songs, root, index) { d, t -> onProgress(BackgroundWork.Stage.ArtistImages, d, t) })
        }

    private fun fromMediaStore(rows: List<MediaStoreRow>, cached: List<Song>): ScanResult {
        // Only already-cached covers (cheap File checks); missing ones are extracted in [enrich].
        val art = rows.map { it.albumId }.filter { it > 0 }.distinct()
            .mapNotNull { id -> artworkCache.cachedPath(id)?.let { id to it } }.toMap()

        val cachedByPath = cached.associateBy { it.filePath }
        val songs = rows.map { r ->
            val (disc, track) = TrackNumbers.split(r.trackNumber)
            Song(
                id = r.id,
                title = r.title.trim().ifEmpty { File(r.filePath).nameWithoutExtension },
                artist = r.artist.trim().ifEmpty { "Unknown Artist" },
                album = r.album.trim().ifEmpty { "Unknown Album" },
                durationMs = r.durationMs,
                filePath = r.filePath,
                artworkPath = art[r.albumId],
                format = r.filePath.substringAfterLast('/').substringAfterLast('.', "").lowercase().ifEmpty { null },
                lastModifiedMs = r.lastModifiedMs,
                fileSize = r.fileSize,
                hasLyrics = cachedByPath[r.filePath]?.hasLyrics ?: false,
                dateAddedMs = r.dateAddedMs.takeIf { it > 0 } ?: r.lastModifiedMs,
                // TRACK is disc×1000+track; genre/bitrate/sampleRate stay null.
                trackNumber = track,
                discNumber = disc,
                albumArtist = r.albumArtist ?: albumArtistFromTag(r, cachedByPath),
                year = r.year.takeIf { it > 0 },
                albumId = r.albumId.takeIf { it > 0 },
                replayGainScanned = false,
            )
        }
        return ScanResult(songs, null)
    }

    /**
     * API < 30 has no public ALBUM_ARTIST column: read the tag (no picture) only for new/changed files;
     * unchanged files keep the cached value.
     */
    private fun albumArtistFromTag(r: MediaStoreRow, cachedByPath: Map<String, Song>): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return null
        val old = cachedByPath[r.filePath]
        if (old != null && old.lastModifiedMs == r.lastModifiedMs && old.fileSize == r.fileSize) return old.albumArtist
        return runCatching { TagReader.read(File(r.filePath), withPicture = false).albumArtist }.getOrNull()
    }

    /** LIB-07; index-only (SAF) matches are copied into `filesDir/artist_images` so they are File-readable. */
    private fun artistImages(
        songs: List<Song>,
        root: String?,
        index: TreeSidecarIndex,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Map<String, String> {
        val scanner = ArtistImageScanner(root, index)
        songs.forEachIndexed { i, s ->
            runCatching { scanner.addSong(s.filePath, s.artist) }
            onProgress(i + 1, songs.size)
        }
        val out = LinkedHashMap<String, String>()
        for ((artist, path) in scanner.images) {
            val f = File(path)
            if (f.canRead()) { out[artist] = path; continue }
            val uri = index.imageUriFor(path) ?: continue
            copyImage(path, uri)?.let { out[artist] = it }
        }
        return out
    }

    private fun copyImage(path: String, uri: Uri): String? = try {
        val ext = path.substringAfterLast('.', "jpg").lowercase()
        val target = File(artistImageDir, "${sha1(path)}.$ext")
        if (!(target.isFile && target.length() > 0)) {
            val tmp = File(target.path + ".tmp")
            resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("no stream")
            if (tmp.length() == 0L || !tmp.renameTo(target)) { tmp.delete(); null } else target.absolutePath
        } else target.absolutePath
    } catch (e: Exception) { null }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
