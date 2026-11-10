package de.yurtemre.sonora.shared.audio.replaygain

/**
 * Random-access byte source for [ReplayGainReader]. [read] returns `null` when the range is out of
 * bounds; it throws [ByteSourceException] when the underlying storage fails (a transient read error).
 */
interface ByteSource {
    val length: Long
    fun read(offset: Long, count: Int): ByteArray?
}

/** Thrown by a [ByteSource] whose underlying storage could not be read. */
class ByteSourceException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** In-memory [ByteSource]. */
class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val length: Long get() = bytes.size.toLong()
    override fun read(offset: Long, count: Int): ByteArray? {
        if (offset < 0 || count < 0 || offset + count > bytes.size) return null
        val start = offset.toInt()
        return bytes.copyOfRange(start, start + count)
    }
}

/** Outcome of a read: [failed] means the source could not be read (retry later). */
data class ReplayGainReadResult(
    val info: de.yurtemre.sonora.shared.model.ReplayGainInfo?,
    val failed: Boolean = false,
) {
    companion object {
        val Failed = ReplayGainReadResult(null, failed = true)
    }
}
