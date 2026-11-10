package de.yurtemre.sonora.shared.platform

import android.content.Context
import android.net.Uri
import de.yurtemre.sonora.shared.repo.api.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** [FileStore] over app-private `filesDir`/`cacheDir` and SAF document URIs. */
class AndroidFileStore(context: Context) : FileStore {
    private val ctx = context.applicationContext

    override fun filesPath(relative: String): String = File(ctx.filesDir, relative).absolutePath
    override fun cachePath(relative: String): String = File(ctx.cacheDir, relative).absolutePath

    override suspend fun exists(path: String): Boolean = io { File(path).exists() }

    override suspend fun readText(path: String): String = io { File(path).readText() }

    override suspend fun writeText(path: String, text: String) = writeBytes(path, text.toByteArray())

    override suspend fun writeBytes(path: String, bytes: ByteArray) = io {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Failed to move ${tmp.path} to ${target.path}")
            }
        }
    }

    override suspend fun delete(path: String): Boolean = io {
        val f = File(path)
        if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    override suspend fun readUriBytes(uri: String): ByteArray = io {
        ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
            ?: throw IOException("Cannot open $uri")
    }

    override suspend fun writeUriText(uri: String, text: String) = io {
        val out = ctx.contentResolver.openOutputStream(Uri.parse(uri), "wt")
            ?: throw IOException("Cannot open $uri for writing")
        out.use { it.write(text.toByteArray()) }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
