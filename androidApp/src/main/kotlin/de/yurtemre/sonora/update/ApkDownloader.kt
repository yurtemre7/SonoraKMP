package de.yurtemre.sonora.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Download progress; [Finished] is emitted exactly once on success. */
sealed interface DownloadEvent {
    data class Progress(val bytes: Long, val total: Long) : DownloadEvent
    data class Finished(val file: File) : DownloadEvent
}

class DownloadHttpException(val code: Int) : IOException("HTTP $code")

/**
 * Streams release assets into `cacheDir/updates/<name>.part` (UPD-08). Cancelling or failing deletes the partial file;
 * success renames it to `<name>`. No resume.
 */
class ApkDownloader(private val client: HttpClient, private val dir: File) {

    fun download(url: String, fileName: String, expectedSize: Long = -1L): Flow<DownloadEvent> = flow {
        dir.mkdirs()
        val part = File(dir, "$fileName.part")
        val target = File(dir, fileName)
        part.delete(); target.delete()
        try {
            client.prepareGet(url).execute { resp ->
                if (!resp.status.isSuccess()) throw DownloadHttpException(resp.status.value)
                val total = resp.contentLength() ?: expectedSize
                val channel = resp.bodyAsChannel()
                val buf = ByteArray(BUFFER)
                var bytes = 0L
                var lastEmit = -1L
                emit(DownloadEvent.Progress(0, total))
                part.outputStream().buffered().use { out ->
                    while (true) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        bytes += n
                        if (bytes - lastEmit >= EMIT_STEP) { lastEmit = bytes; emit(DownloadEvent.Progress(bytes, total)) }
                    }
                }
                if (total > 0 && bytes != total) throw IOException("Truncated download: $bytes of $total")
                emit(DownloadEvent.Progress(bytes, total))
            }
            if (!part.renameTo(target)) throw IOException("Rename failed")
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        emit(DownloadEvent.Finished(target))
    }.flowOn(Dispatchers.IO)

    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val resp = client.get(url)
        if (!resp.status.isSuccess()) throw DownloadHttpException(resp.status.value)
        resp.bodyAsText()
    }

    /** Removes leftovers of earlier downloads. */
    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private companion object {
        const val BUFFER = 64 * 1024
        const val EMIT_STEP = 128 * 1024L
    }
}
