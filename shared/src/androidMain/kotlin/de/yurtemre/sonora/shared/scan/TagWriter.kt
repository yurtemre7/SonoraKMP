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

    /**
     * The file's own tags as an editable form (what is really stored, not what the library row happens to hold),
     * or null when the file can't be read. [fallbackTitle] is used when the file has no title tag.
     */
    fun readEditable(file: File, fallbackTitle: String): TagEdit? = try {
        val tag: Tag? = AudioFileIO.read(file).tag
        fun f(k: FieldKey) = try { tag?.getFirst(k)?.trim().orEmpty() } catch (_: Throwable) { "" }
        TagEdit(
            title = f(FieldKey.TITLE).ifEmpty { fallbackTitle },
            artist = f(FieldKey.ARTIST),
            album = f(FieldKey.ALBUM),
            albumArtist = f(FieldKey.ALBUM_ARTIST),
            genre = f(FieldKey.GENRE),
            year = TagReader.leadingInt(f(FieldKey.YEAR))?.toString().orEmpty(),
            track = TagReader.leadingInt(f(FieldKey.TRACK))?.toString().orEmpty(),
            disc = TagReader.leadingInt(f(FieldKey.DISC_NO))?.toString().orEmpty(),
            lyrics = try { tag?.getFirst(FieldKey.LYRICS).orEmpty() } catch (_: Throwable) { "" },
        )
    } catch (_: Throwable) { null }

    /**
     * Writes [edit] into [file]'s tags, touching only the fields that differ from [baseline] (the form as loaded
     * from this file), so e.g. a title-only edit leaves genre, lyrics and full dates alone. Blank values remove
     * that field. Throws on unsupported or damaged files, so the caller can leave the original untouched.
     */
    fun write(file: File, edit: TagEdit, baseline: TagEdit) {
        val e = edit.normalized()
        val b = baseline.normalized()
        val audio = AudioFileIO.read(file)
        val tag: Tag = audio.tagOrCreateAndSetDefault
        if (e.title != b.title) set(tag, FieldKey.TITLE, e.title)
        if (e.artist != b.artist) set(tag, FieldKey.ARTIST, e.artist)
        if (e.album != b.album) set(tag, FieldKey.ALBUM, e.album)
        if (e.albumArtist != b.albumArtist) set(tag, FieldKey.ALBUM_ARTIST, e.albumArtist)
        if (e.genre != b.genre) set(tag, FieldKey.GENRE, e.genre)
        if (e.year != b.year) set(tag, FieldKey.YEAR, e.yearOrNull()?.toString().orEmpty())
        if (e.track != b.track) set(tag, FieldKey.TRACK, e.trackOrNull()?.toString().orEmpty())
        if (e.disc != b.disc) set(tag, FieldKey.DISC_NO, e.discOrNull()?.toString().orEmpty())
        if (e.lyrics != b.lyrics) set(tag, FieldKey.LYRICS, e.lyrics)
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
