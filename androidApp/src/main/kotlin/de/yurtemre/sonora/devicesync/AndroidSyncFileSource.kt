package de.yurtemre.sonora.devicesync

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import de.yurtemre.sonora.shared.devicesync.ManifestFile
import de.yurtemre.sonora.shared.devicesync.SyncFileSource
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Opens only the files the [SenderManifestBuilder] recorded (id -> file), and only when the requested
 * [ManifestFile.relPath] matches the recorded one. Anything else is an [IOException].
 */
class AndroidSyncFileSource(
    private val resolver: ContentResolver,
    private val entries: Map<Int, Entry>,
) : SyncFileSource {
    /** [uri] is the SAF document Uri in folder mode; otherwise the file is read by [path]. */
    data class Entry(val relPath: String, val path: String, val uri: Uri?, val root: String)

    override fun open(file: ManifestFile): InputStream {
        val e = entries[file.id]
        if (e == null || e.relPath != file.relPath) throw IOException("unknown_file")
        return try {
            if (e.uri != null) {
                resolver.openInputStream(e.uri) ?: throw IOException("open_failed")
            } else {
                openFileOrMediaStore(e.path, e.root)
            }
        } catch (ex: IOException) {
            throw ex
        } catch (ex: Exception) {
            throw IOException("open_failed", ex)
        }
    }

    private fun openFileOrMediaStore(path: String, root: String): InputStream {
        try {
            val file = File(path)
            val canonRoot = File(root.ifEmpty { "/" }).canonicalPath
            val prefix = if (canonRoot.endsWith("/")) canonRoot else "$canonRoot/"
            // A symlink must not lead outside the music root.
            if (!file.canonicalPath.startsWith(prefix)) throw IOException("outside_root")
            return file.inputStream()
        } catch (_: IOException) {
            // API 30+: java.io.File may be denied for media files; fall back to the MediaStore content Uri.
        } catch (_: SecurityException) {
        }
        val uri = mediaStoreUri(path) ?: throw FileNotFoundException("open_failed")
        return resolver.openInputStream(uri) ?: throw IOException("open_failed")
    }

    private fun mediaStoreUri(path: String): Uri? {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        resolver.query(base, arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.Audio.Media.DATA} = ?", arrayOf(path), null)
            ?.use { c -> if (c.moveToFirst()) return Uri.withAppendedPath(base, c.getLong(0).toString()) }
        return null
    }
}
