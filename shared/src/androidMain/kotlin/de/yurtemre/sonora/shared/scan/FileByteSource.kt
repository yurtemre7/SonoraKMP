package de.yurtemre.sonora.shared.scan

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import de.yurtemre.sonora.shared.audio.replaygain.ByteSource
import de.yurtemre.sonora.shared.audio.replaygain.ByteSourceException
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** File-backed [ByteSource] using positional channel reads. Close after use. */
class FileByteSource private constructor(
    private val channel: FileChannel,
    private val closeable: Closeable,
) : ByteSource, Closeable {
    override val length: Long = try { channel.size() } catch (e: IOException) { throw ByteSourceException("size", e) }

    override fun read(offset: Long, count: Int): ByteArray? {
        if (offset < 0 || count < 0 || offset + count > length) return null
        val buf = ByteBuffer.allocate(count)
        try {
            var pos = offset
            while (buf.hasRemaining()) {
                val n = channel.read(buf, pos)
                if (n < 0) return null
                pos += n
            }
        } catch (e: IOException) {
            throw ByteSourceException("read failed at $offset", e)
        }
        return buf.array()
    }

    override fun close() { try { closeable.close() } catch (_: IOException) {} }

    companion object {
        /** @throws ByteSourceException when the file cannot be opened. */
        fun open(file: File): FileByteSource = try {
            val raf = RandomAccessFile(file, "r")
            FileByteSource(raf.channel, raf)
        } catch (e: Exception) { throw ByteSourceException("open ${file.path}", e) }

        /** Opens a content:// (or file://) uri via [resolver]. @throws ByteSourceException */
        fun open(resolver: ContentResolver, uri: Uri): FileByteSource = try {
            val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r")
                ?: throw IOException("null descriptor")
            val stream = FileInputStream(pfd.fileDescriptor)
            FileByteSource(stream.channel, Closeable { stream.close(); pfd.close() })
        } catch (e: ByteSourceException) { throw e } catch (e: Exception) { throw ByteSourceException("open $uri", e) }
    }
}
