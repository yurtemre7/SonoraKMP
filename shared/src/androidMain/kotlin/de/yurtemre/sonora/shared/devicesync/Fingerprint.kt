package de.yurtemre.sonora.shared.devicesync

import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest

/**
 * The quick file fingerprint used to decide ambiguous plan cases without hashing whole files (DSYNC-08):
 * hex SHA-256 over (size as 8-byte big-endian ‖ head ‖ tail), where head is the first `min(64 KiB, size)` bytes
 * and tail the last `min(64 KiB, size)` bytes. For files up to 128 KiB the two windows overlap (for files up to
 * 64 KiB both are the whole file); the overlapping bytes are hashed twice. Both phones compute it the same way.
 */
object Fingerprint {
    const val WINDOW = 64 * 1024

    fun compute(size: Long, head: ByteArray, tail: ByteArray): String {
        val window = windowFor(size)
        require(head.size == window && tail.size == window) { "Head and tail must be $window bytes" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteArray(8) { (size ushr (56 - 8 * it)).toByte() })
        digest.update(head)
        digest.update(tail)
        return digest.digest().toHex()
    }

    /**
     * Reads the head and tail through [open] (called once, or twice for files over 64 KiB) and computes the
     * fingerprint of a file of [size] bytes. Throws [EOFException] if a stream ends early.
     */
    fun of(size: Long, open: () -> InputStream): String {
        val window = windowFor(size)
        val head = open().use { it.readExactly(window) }
        val tail = if (size <= WINDOW) {
            head
        } else {
            open().use {
                it.skipExactly(size - window)
                it.readExactly(window)
            }
        }
        return compute(size, head, tail)
    }

    private fun windowFor(size: Long): Int {
        require(size >= 0) { "Negative size" }
        return minOf(WINDOW.toLong(), size).toInt()
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = read(bytes, offset, count - offset)
            if (n < 0) throw EOFException("File shorter than expected")
            offset += n
        }
        return bytes
    }

    private fun InputStream.skipExactly(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                if (read() < 0) throw EOFException("File shorter than expected")
                left--
            }
        }
    }
}

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val chars = CharArray(size * 2)
    forEachIndexed { i, b ->
        chars[2 * i] = digits[(b.toInt() ushr 4) and 0xF]
        chars[2 * i + 1] = digits[b.toInt() and 0xF]
    }
    return String(chars)
}
