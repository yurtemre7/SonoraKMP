package de.yurtemre.sonora.shared.scan

import android.content.ContentResolver
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** Raw MediaStore row (LIB-03); art & artist images are resolved by other components. */
data class MediaStoreRow(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val filePath: String,
    val albumId: Long,
    /** Raw TRACK column (disc×1000+track). */
    val trackNumber: Int,
    val year: Int,
    val lastModifiedMs: Long,
    /** DATE_ADDED (s → ms); 0 when unknown. */
    val dateAddedMs: Long = 0,
    /** ALBUM_ARTIST (API 30+ column); null when absent/blank. */
    val albumArtist: String? = null,
    val fileSize: Long,
    val hasLyrics: Boolean,
)

/** Port of Flutter `queryMediaStore` (audit/data §2.3). */
class MediaStoreScanner(private val resolver: ContentResolver) {

    /** Returns rows, or null when the query failed (cursor null / exception). */
    fun query(folderPath: String?): List<MediaStoreRow>? {
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED,
        )
        // ALBUM_ARTIST is a public column from API 30; older devices fall back to the file tag.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) projection.add(MediaStore.Audio.Media.ALBUM_ARTIST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) projection.add(MediaStore.Audio.Media.COMPOSER)

        val variants = folderVariants(folderPath)
        val (selection, args) = selection(variants)
        return try {
            val cursor = resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection.toTypedArray(), selection, args, null,
            ) ?: return null
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val modCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val albumArtistCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1
                val out = ArrayList<MediaStoreRow>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) {
                    val path = c.getString(dataCol)
                    if (path.isNullOrEmpty()) continue
                    if (!isInFolder(path, variants)) continue
                    val rawTitle = c.getString(titleCol) ?: ""
                    val rawArtist = c.getString(artistCol) ?: ""
                    val rawAlbum = c.getString(albumCol) ?: ""
                    out += MediaStoreRow(
                        id = c.getLong(idCol),
                        title = rawTitle.ifEmpty { File(path).nameWithoutExtension },
                        artist = if (rawArtist.isNotEmpty() && rawArtist != UNKNOWN) rawArtist else "Unknown Artist",
                        album = if (rawAlbum.isNotEmpty() && rawAlbum != UNKNOWN) rawAlbum else "Unknown Album",
                        durationMs = c.getLong(durCol),
                        filePath = path,
                        albumId = c.getLong(albumIdCol),
                        trackNumber = c.getInt(trackCol),
                        year = c.getInt(yearCol),
                        lastModifiedMs = c.getLong(modCol) * 1000L,
                        fileSize = c.getLong(sizeCol),
                        dateAddedMs = c.getLong(addedCol) * 1000L,
                        albumArtist = if (albumArtistCol >= 0) c.getString(albumArtistCol)?.trim()
                            ?.takeIf { it.isNotEmpty() && it != UNKNOWN } else null,
                        hasLyrics = false, // resolved in the background (AndroidLibraryScanner.enrich)
                    )
                }
                out
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val UNKNOWN = "<unknown>"

        fun folderVariants(folderPath: String?): Set<String> {
            val norm = folderPath?.trimEnd('/', '\\')?.replace('\\', '/')
            val v = linkedSetOf<String>()
            if (norm.isNullOrEmpty()) return v
            v += norm
            when {
                norm.equals("/sdcard", true) || norm.startsWith("/sdcard/", true) ->
                    v += norm.replaceFirst("/sdcard", "/storage/emulated/0", ignoreCase = true)
                norm.equals("/storage/emulated/0", true) || norm.startsWith("/storage/emulated/0/", true) ->
                    v += norm.replaceFirst("/storage/emulated/0", "/sdcard", ignoreCase = true)
            }
            return v
        }

        fun escapeLike(value: String): String =
            value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        fun selection(variants: Set<String>): Pair<String, Array<String>> {
            val folderSel = if (variants.isEmpty()) "" else variants.joinToString(" OR ", " AND (", ")") {
                "${MediaStore.Audio.Media.DATA} LIKE ? ESCAPE '\\'"
            }
            return "${MediaStore.Audio.Media.IS_MUSIC} != 0$folderSel" to
                variants.map { "${escapeLike(it)}/%" }.toTypedArray()
        }

        fun isInFolder(path: String, variants: Set<String>): Boolean {
            if (variants.isEmpty()) return true
            val p = path.replace('\\', '/')
            return variants.any { p.startsWith("$it/", ignoreCase = true) }
        }
    }
}
