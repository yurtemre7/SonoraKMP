package de.yurtemre.sonora.platform

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import de.yurtemre.sonora.shared.library.TagEdit
import de.yurtemre.sonora.shared.scan.TagWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scoped-storage plumbing for the tag editor. The app only holds READ_MEDIA_AUDIO, so writing a tag goes through
 * the song's MediaStore URI: the user approves a system prompt, the audio is copied to a temp file, edited with
 * jaudiotagger and copied back. The original is only overwritten after the temp copy was edited successfully.
 */
object TagFileAccess {
    /** MediaStore URI of the file at [path], or null when it is not indexed (e.g. a folder only reachable via SAF). */
    suspend fun uriFor(context: Context, path: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DATA} = ?", arrayOf(path), null,
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
        }.getOrNull()
    }

    /**
     * The system prompt to launch before writing [uri], or null when none is needed. API 30+: write request
     * (unless this process already holds the grant); API 29: only when a probe throws RecoverableSecurityException.
     */
    fun writeRequest(context: Context, uri: Uri): IntentSender? {
        if (Build.VERSION.SDK_INT >= 30) {
            val granted = context.checkUriPermission(
                uri, android.os.Process.myPid(), android.os.Process.myUid(), android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            return if (granted) null else MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
        }
        if (Build.VERSION.SDK_INT == 29) {
            try {
                context.contentResolver.openFileDescriptor(uri, "rw")?.close()
            } catch (e: RecoverableSecurityException) {
                return e.userAction.actionIntent.intentSender
            } catch (_: Exception) {}
        }
        return null
    }

    /** Lyrics currently stored in the file's tags: "" when none, null when the file couldn't be read. */
    suspend fun readLyrics(context: Context, uri: Uri, ext: String): String? = withContext(Dispatchers.IO) {
        val temp = copyOut(context, uri, ext) ?: return@withContext null
        try { TagWriter.readLyrics(temp) } finally { temp.delete() }
    }

    /**
     * Writes [edit] into the file behind [uri]; [writeLyrics] = false leaves the lyrics tag alone. Returns the
     * failure, or null on success. The untouched original is kept in a temp copy until the new bytes are fully
     * written; if writing fails midway it is written back, and if even that fails it is kept in `tag_recovery/`.
     */
    suspend fun write(context: Context, uri: Uri, path: String, edit: TagEdit, writeLyrics: Boolean = true): Throwable? =
        withContext(Dispatchers.IO) {
            val ext = path.substringAfterLast('.', "")
            var original: File? = null
            var edited: File? = null
            try {
                original = copyOut(context, uri, ext) ?: return@withContext IllegalStateException("cannot read file")
                edited = File.createTempFile("tagedit_", if (ext.isEmpty()) null else ".$ext", context.cacheDir)
                original.copyTo(edited, overwrite = true)
                TagWriter.write(edited, edit, writeLyrics) // throws on unsupported/damaged files; the original is untouched until here
                try {
                    overwrite(context, uri, edited)
                } catch (t: Throwable) {
                    // "wt" truncated the file before failing: put the original bytes back.
                    try { overwrite(context, uri, original) } catch (restore: Throwable) {
                        t.addSuppressed(restore)
                        val kept = File(context.cacheDir, "tag_recovery").apply { mkdirs() }
                            .let { File(it, "${System.currentTimeMillis()}_${path.substringAfterLast('/')}") }
                        runCatching { original.copyTo(kept, overwrite = true) }
                    }
                    throw t
                }
                MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                null
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                t
            } finally {
                edited?.delete()
                original?.delete() // a copy was saved to tag_recovery/ first if the restore failed
            }
        }

    private fun overwrite(context: Context, uri: Uri, source: File) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("cannot open file")
        out.use { o -> source.inputStream().use { it.copyTo(o) } }
    }

    private fun copyOut(context: Context, uri: Uri, ext: String): File? {
        var t: File? = null
        return try {
            t = File.createTempFile("tagedit_", if (ext.isEmpty()) null else ".$ext", context.cacheDir)
            val ok = context.contentResolver.openInputStream(uri)?.use { i -> t.outputStream().use { i.copyTo(it) }; true } ?: false
            if (ok) t else { t.delete(); null }
        } catch (_: Exception) {
            t?.delete() // never hand back (or leak) a partial copy
            null
        }
    }
}
