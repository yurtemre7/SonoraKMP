package de.yurtemre.sonora.shared.scan

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

/**
 * LIB-05: MediaStore album-art cache in `cacheDir/album_art`.
 * `art_album_<id>.jpg` holds art, `art_album_<id>.none` marks "no art" (never retried) and is only
 * written after every fallback failed: MediaStore thumbnail → MediaMetadataRetriever (path / content
 * Uri) → [TagReader] picture (incl. Ogg `METADATA_BLOCK_PICTURE`). Markers from older builds (without
 * [MARKER_VERSION]) are ignored so those albums are retried. Writes go through `.jpg.tmp` + rename.
 */
class ArtworkCache(private val context: Context) {

    val dir: File = File(context.cacheDir, "album_art")
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun artFile(albumId: Long) = File(dir, "art_album_$albumId.jpg")
    fun noneMarker(albumId: Long) = File(dir, "art_album_$albumId.none")

    /** Cached path, or null (unknown or known to have no art). */
    fun cachedPath(albumId: Long): String? =
        artFile(albumId).takeIf { it.isFile && it.length() > 0 }?.absolutePath

    /** A song of the album whose embedded picture can serve as fallback. */
    data class Source(val filePath: String, val songId: Long)

    /** True when the album still needs an extraction attempt. */
    fun needsExtraction(albumId: Long): Boolean =
        albumId > 0 && cachedPath(albumId) == null && !hasValidMarker(albumId)

    private fun hasValidMarker(albumId: Long): Boolean =
        runCatching { noneMarker(albumId).let { it.isFile && it.readText() == MARKER_VERSION } }.getOrDefault(false)

    /**
     * Extracts one album's art. Returns path, or null. The `.none` marker is written only when the
     * MediaStore thumbnail and every embedded-picture fallback came back empty; other errors retry later.
     */
    fun extract(albumId: Long, source: Source? = null): String? {
        if (albumId <= 0) return null
        cachedPath(albumId)?.let { return it }
        if (hasValidMarker(albumId)) return null
        dir.mkdirs()
        val target = artFile(albumId)
        val tmp = File(dir, "art_album_$albumId.jpg.tmp")
        var transientError = false
        val fromStore = try {
            fromMediaStore(albumId, tmp)
        } catch (_: FileNotFoundException) {
            false
        } catch (_: Throwable) {
            transientError = true
            false
        }
        val ok = fromStore || (source != null && fromEmbedded(source, tmp))
        return when {
            ok && tmp.length() > 0 && tmp.renameTo(target) -> target.absolutePath
            else -> {
                tmp.delete()
                if (!transientError || source != null) markNone(albumId)
                null
            }
        }
    }

    private fun fromMediaStore(albumId: Long, tmp: File): Boolean {
        val uri = ContentUris.withAppendedId(ALBUM_ART_URI, albumId)
        if (Build.VERSION.SDK_INT >= 29) {
            val bmp = context.contentResolver.loadThumbnail(uri, Size(SIZE, SIZE), null)
            try {
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            } finally {
                bmp.recycle()
            }
        } else {
            val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
            input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        }
        return tmp.length() > 0
    }

    /** MediaMetadataRetriever (path, then content Uri), then [TagReader]; re-encoded as ≤720 px q90 JPEG. */
    private fun fromEmbedded(source: Source, tmp: File): Boolean {
        val bytes = retrieverPicture { it.setDataSource(source.filePath) }
            ?: retrieverPicture {
                it.setDataSource(context, ContentUris.withAppendedId(AUDIO_URI, source.songId))
            }
            ?: runCatching { TagReader.read(File(source.filePath), withPicture = true).picture }.getOrNull()
            ?: return false
        return writeJpeg(bytes, tmp)
    }

    private fun retrieverPicture(setSource: (MediaMetadataRetriever) -> Unit): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            setSource(r)
            r.embeddedPicture?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun writeJpeg(bytes: ByteArray, tmp: File): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (decoded == null) {
            false
        } else {
            val scale = SIZE.toFloat() / maxOf(decoded.width, decoded.height)
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
            } else decoded
            try {
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            } finally {
                if (bmp !== decoded) bmp.recycle()
                decoded.recycle()
            }
            tmp.length() > 0
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Cold mode: extracts all [albumIds] on a pool of clamp(cores,1,4) threads, bounded by 5 min.
     * Returns albumId → path for those that succeeded.
     */
    suspend fun extractAll(albumIds: Collection<Long>, sources: Map<Long, Source> = emptyMap()): Map<Long, String> {
        val ids = albumIds.filter { needsExtraction(it) }.distinct()
        val result = HashMap<Long, String>()
        albumIds.forEach { id -> cachedPath(id)?.let { result[id] = it } }
        if (ids.isEmpty()) return result
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val pool = Executors.newFixedThreadPool(threads).asCoroutineDispatcher()
        try {
            val found = java.util.concurrent.ConcurrentHashMap<Long, String>()
            withTimeoutOrNull(COLD_TIMEOUT_MS) {
                withContext(pool) {
                    ids.map { id -> async { extract(id, sources[id])?.let { found[id] = it } } }.awaitAll()
                }
            }
            result.putAll(found)
        } finally {
            pool.close()
        }
        return result
    }

    /** Warm mode: extracts in the background and reports found paths via [onExtracted] (D-20: caller patches DB rows). */
    fun extractInBackground(
        albumIds: Collection<Long>,
        sources: Map<Long, Source> = emptyMap(),
        onExtracted: suspend (Map<Long, String>) -> Unit) {
        val ids = albumIds.filter { needsExtraction(it) }.distinct()
        if (ids.isEmpty()) return
        bgScope.launch {
            val found = HashMap<Long, String>()
            for (id in ids) extract(id, sources[id])?.let { found[id] = it }
            if (found.isNotEmpty()) runCatching { onExtracted(found) }
        }
    }

    /** Deletes cache entries (jpg / none / jpg.tmp) whose album id is not in [keepAlbumIds]. */
    fun prune(keepAlbumIds: Set<Long>) {
        dir.listFiles()?.forEach { f ->
            val m = PRUNE_REGEX.matchEntire(f.name) ?: return@forEach
            val id = m.groupValues[1].toLongOrNull() ?: return@forEach
            if (id !in keepAlbumIds) f.delete()
        }
    }

    private fun markNone(albumId: Long) {
        runCatching { dir.mkdirs(); noneMarker(albumId).writeText(MARKER_VERSION) }
    }

    companion object {
        const val SIZE = 720
        const val QUALITY = 90
        /** Marker content; markers without it predate the embedded-picture fallbacks and are retried. */
        const val MARKER_VERSION = "v2"
        const val COLD_TIMEOUT_MS = 5 * 60 * 1000L
        private val ALBUM_ART_URI = android.net.Uri.parse("content://media/external/audio/albumart")
        private val AUDIO_URI = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        private val PRUNE_REGEX = Regex("""^art_album_(\d+)\.(jpg|none|jpg\.tmp)$""")
    }
}
