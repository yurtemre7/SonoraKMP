package de.yurtemre.sonora.shared.scan

import android.media.MediaMetadataRetriever
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Tags read from a file (LIB-06). All fields nullable; blank strings normalised to null. */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val genre: String? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    /** kbps. */
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val format: String? = null,
    val picture: ByteArray? = null,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** jaudiotagger first, [MediaMetadataRetriever] fallback/fill-in. Never throws. */
object TagReader {
    init {
        try { Logger.getLogger("org.jaudiotagger").level = Level.OFF } catch (_: Throwable) {}
    }

    fun read(file: File, withPicture: Boolean = true): AudioTags {
        val tags = readTags(file, withPicture)
        if (!withPicture || tags.picture != null || file.extension.lowercase() !in OGG_EXTENSIONS) return tags
        return OggPictureReader.read(file)?.let { tags.copy(picture = it) } ?: tags
    }

    private val OGG_EXTENSIONS = setOf("opus", "ogg", "oga")

    private fun readTags(file: File, withPicture: Boolean): AudioTags {
        val primary = try { readJaudiotagger(file, withPicture) } catch (_: Throwable) { null }
        val needsFallback = primary == null || primary.title == null || primary.durationMs == null ||
            (withPicture && primary.picture == null)
        if (!needsFallback) return primary!!
        val fb = try { readRetriever(file, withPicture) } catch (_: Throwable) { null }
        return when {
            primary == null -> fb ?: AudioTags(format = file.extension.lowercase().ifEmpty { null })
            fb == null -> primary
            else -> primary.copy(
                title = primary.title ?: fb.title,
                artist = primary.artist ?: fb.artist,
                albumArtist = primary.albumArtist ?: fb.albumArtist,
                album = primary.album ?: fb.album,
                trackNumber = primary.trackNumber ?: fb.trackNumber,
                discNumber = primary.discNumber ?: fb.discNumber,
                genre = primary.genre ?: fb.genre,
                year = primary.year ?: fb.year,
                durationMs = primary.durationMs ?: fb.durationMs,
                bitrate = primary.bitrate ?: fb.bitrate,
                sampleRate = primary.sampleRate ?: fb.sampleRate,
                picture = primary.picture ?: fb.picture,
            )
        }
    }

    private fun readJaudiotagger(file: File, withPicture: Boolean): AudioTags {
        val af = AudioFileIO.read(file)
        val h = af.audioHeader
        val t: Tag? = af.tag
        fun f(k: FieldKey) = try { t?.getFirst(k)?.trim()?.ifEmpty { null } } catch (_: Throwable) { null }
        val durMs = h?.let { runCatching { (it.preciseTrackLength * 1000).toLong() }.getOrNull() }?.takeIf { it > 0 }
        return AudioTags(
            title = f(FieldKey.TITLE),
            artist = f(FieldKey.ARTIST),
            albumArtist = f(FieldKey.ALBUM_ARTIST),
            album = f(FieldKey.ALBUM),
            trackNumber = leadingInt(f(FieldKey.TRACK)),
            discNumber = leadingInt(f(FieldKey.DISC_NO)),
            genre = f(FieldKey.GENRE),
            year = leadingInt(f(FieldKey.YEAR)),
            durationMs = durMs,
            bitrate = h?.let { runCatching { it.bitRateAsNumber.toInt() }.getOrNull() }?.takeIf { it > 0 },
            sampleRate = h?.let { runCatching { it.sampleRateAsNumber }.getOrNull() }?.takeIf { it > 0 },
            format = file.extension.lowercase().ifEmpty { null },
            picture = if (withPicture) try { t?.firstArtwork?.binaryData?.takeIf { it.isNotEmpty() } } catch (_: Throwable) { null } else null,
        )
    }

    private fun readRetriever(file: File, withPicture: Boolean): AudioTags {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            fun m(k: Int) = r.extractMetadata(k)?.trim()?.ifEmpty { null }
            val sr = if (android.os.Build.VERSION.SDK_INT >= 31) leadingInt(m(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)) else null
            return AudioTags(
                title = m(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = m(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = m(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                trackNumber = leadingInt(m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNumber = leadingInt(m(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                genre = m(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = leadingInt(m(MediaMetadataRetriever.METADATA_KEY_YEAR)),
                durationMs = m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 },
                bitrate = m(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { (it / 1000).toInt() }?.takeIf { it > 0 },
                sampleRate = sr,
                format = file.extension.lowercase().ifEmpty { null },
                picture = if (withPicture) r.embeddedPicture?.takeIf { it.isNotEmpty() } else null,
            )
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
    }

    private val leading = Regex("^\\s*(\\d+)")
    internal fun leadingInt(s: String?): Int? = s?.let { leading.find(it)?.groupValues?.get(1)?.toIntOrNull() }
}
