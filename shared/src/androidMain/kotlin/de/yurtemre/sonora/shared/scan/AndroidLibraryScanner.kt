package de.yurtemre.sonora.shared.scan

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.platform.TreeUriPaths
import de.yurtemre.sonora.shared.repo.api.LibraryScanner
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

        val index = TreeSidecarIndex.build(resolver, treeUri)
        val rows = mediaStore.query(root)

        if (!rows.isNullOrEmpty()) return fromMediaStore(rows, root, index, cached)

        if (root == null) {
            // No folder and MediaStore empty: keep cached songs whose file still exists (audit §2.2 step 4).
            val kept = cached.filter { File(it.filePath).exists() }
                .map { it.withLyrics(index) }
            return ScanResult(kept, artistImages(kept, null, index))
        }

        val minNextId = maxOf(
            rows.orEmpty().maxOfOrNull { it.id } ?: 0L,
            cached.maxOfOrNull { it.id } ?: 0L,
        ) + 1
        val dir = directoryScanner.scan(root, treeUri, cached, minNextId) ?: return null
        val songs = dir.songs.map { it.withLyrics(index) }
        return ScanResult(songs, artistImages(songs, root, index))
    }

    private suspend fun fromMediaStore(
        rows: List<MediaStoreRow>,
        root: String?,
        index: TreeSidecarIndex,
        cached: List<Song>,
    ): ScanResult {
        val albumIds = rows.map { it.albumId }.filter { it > 0 }.toSet()
        val known = albumIds.associateWith { artworkCache.cachedPath(it) }
        val missing = albumIds.filter { artworkCache.needsExtraction(it) }
        val coldCache = known.values.all { it == null } && missing.isNotEmpty()
        val art: Map<Long, String> = if (coldCache) {
            // Cold: extract synchronously (pool clamp(cores,1,4), ≤ 5 min) and patch rows now.
            artworkCache.extractAll(albumIds)
        } else {
            if (missing.isNotEmpty()) {
                artworkCache.extractInBackground(missing) { found -> _backgroundArt.emit(found) }
            }
            known.filterValues { it != null }.mapValues { it.value!! }
        }

        val songs = rows.map { r ->
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
                hasLyrics = r.hasLyrics || LyricsLocator.hasLyrics(r.filePath, index),
                // LIB-03: TRACK stays raw (disc×1000+track); disc/genre/bitrate/sampleRate null.
                trackNumber = r.trackNumber.takeIf { it > 0 },
                discNumber = null,
                year = r.year.takeIf { it > 0 },
                albumId = r.albumId.takeIf { it > 0 },
                replayGainScanned = false,
            )
        }
        runCatching { artworkCache.prune(albumIds) }
        return ScanResult(songs, artistImages(songs, root, index))
    }

    private fun Song.withLyrics(index: TreeSidecarIndex): Song {
        val has = LyricsLocator.hasLyrics(filePath, index)
        return if (has == hasLyrics) this else copy(hasLyrics = has)
    }

    /** LIB-07; index-only (SAF) matches are copied into `filesDir/artist_images` so they are File-readable. */
    private fun artistImages(songs: List<Song>, root: String?, index: TreeSidecarIndex): Map<String, String> {
        val scanner = ArtistImageScanner(root, index)
        for (s in songs) runCatching { scanner.addSong(s.filePath, s.artist) }
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
