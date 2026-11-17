package de.yurtemre.sonora.shared.scan

import de.yurtemre.sonora.shared.library.TagEdit
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Reads and writes the editable tag fields with jaudiotagger. Works on a real [File]: callers copy the audio
 * file to a temp file first when they only have a content URI (scoped storage), then copy the result back.
 */
object TagWriter {
    init {
        try { Logger.getLogger("org.jaudiotagger").level = Level.OFF } catch (_: Throwable) {}
    }

    /** Lyrics text stored in the file's tags (raw, not parsed): "" when there are none, null when the file can't be read. */
    fun readLyrics(file: File): String? = try {
        AudioFileIO.read(file).tag?.getFirst(FieldKey.LYRICS).orEmpty()
    } catch (_: Throwable) { null }

    /**
     * Writes [edit] into [file]'s tags. Blank values remove that field. Throws on unsupported or damaged files,
     * so the caller can leave the original untouched.
     */
    fun write(file: File, edit: TagEdit, writeLyrics: Boolean = true) {
        val e = edit.normalized()
        val audio = AudioFileIO.read(file)
        val tag: Tag = audio.tagOrCreateAndSetDefault
        set(tag, FieldKey.TITLE, e.title)
        set(tag, FieldKey.ARTIST, e.artist)
        set(tag, FieldKey.ALBUM, e.album)
        set(tag, FieldKey.ALBUM_ARTIST, e.albumArtist)
        set(tag, FieldKey.GENRE, e.genre)
        set(tag, FieldKey.YEAR, e.yearOrNull()?.toString().orEmpty())
        set(tag, FieldKey.TRACK, e.trackOrNull()?.toString().orEmpty())
        set(tag, FieldKey.DISC_NO, e.discOrNull()?.toString().orEmpty())
        if (writeLyrics) set(tag, FieldKey.LYRICS, e.lyrics) // skipped when the current lyrics couldn't be read
        audio.commit()
    }

    private fun set(tag: Tag, key: FieldKey, value: String) {
        if (value.isEmpty()) {
            try { tag.deleteField(key) } catch (_: Throwable) {}
        } else {
            tag.setField(key, value)
        }
    }
}
