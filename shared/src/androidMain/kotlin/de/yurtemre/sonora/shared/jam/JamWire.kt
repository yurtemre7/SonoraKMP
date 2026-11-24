package de.yurtemre.sonora.shared.jam

import de.yurtemre.sonora.shared.devicesync.DeviceSyncProtocolException
import de.yurtemre.sonora.shared.devicesync.Frame
import de.yurtemre.sonora.shared.devicesync.FrameCodec
import de.yurtemre.sonora.shared.devicesync.SecureChannel
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

/**
 * How Jam frames travel, shared by [JamServer] and [JamClient].
 *
 * - **Preamble** (clear, before anything else): `"SJAM"` + mode byte, [MODE_JOIN] or [MODE_RECONNECT]. A device
 *   sync client (or anything else) that connects is closed on the bad magic.
 * - **Join**: then the unchanged [de.yurtemre.sonora.shared.devicesync.JpakeHandshake] with the Jam code (host =
 *   `Role.Sender`, guest = `Role.Receiver`) and a [SecureChannel] on its keying material.
 * - **Reconnect**: guest → `[idLen:u16][guestId UTF-8][clientNonce:32]`; host → `[status:u8]` and, on [STATUS_OK],
 *   `[serverNonce:32]`; both then open a [SecureChannel] on [reconnectKeyingMaterial]. The guest proves the key by
 *   its first record (an AES-GCM tag that only the key holder can produce), the host by its answer.
 * - **Frames**: the unchanged [SecureChannel] carries device sync [Frame]s, so one [JamFrameCodec] encoding is sent
 *   as consecutive [Frame.Chunk]s of [FrameCodec.CHUNK] bytes; a shorter (possibly empty) chunk ends the frame.
 */
internal object JamWire {
    const val MODE_JOIN = 1
    const val MODE_RECONNECT = 2
    const val STATUS_OK = 0
    const val STATUS_REFUSED = 1
    const val NONCE_SIZE = 32
    const val KEY_SIZE = 16
    private const val MAX_ID_BYTES = 64
    private val MAGIC = "SJAM".encodeToByteArray()
    private val RECONNECT_LABEL = "sonora-jam reconnect v1".encodeToByteArray()

    fun writePreamble(output: DataOutputStream, mode: Int) {
        output.write(MAGIC)
        output.writeByte(mode)
        output.flush()
    }

    /** Returns the mode; anything but a Jam preamble → [DeviceSyncProtocolException]. */
    fun readPreamble(input: DataInputStream): Int {
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        if (!magic.contentEquals(MAGIC)) throw DeviceSyncProtocolException("Not a Jam connection")
        val mode = input.readUnsignedByte()
        if (mode != MODE_JOIN && mode != MODE_RECONNECT) throw DeviceSyncProtocolException("Unknown Jam mode $mode")
        return mode
    }

    fun writeReconnectRequest(output: DataOutputStream, guestId: String, clientNonce: ByteArray) {
        val id = guestId.encodeToByteArray()
        require(id.size in 1..MAX_ID_BYTES) { "Bad guest id" }
        output.writeShort(id.size)
        output.write(id)
        output.write(clientNonce)
        output.flush()
    }

    /** Returns (guestId, clientNonce). */
    fun readReconnectRequest(input: DataInputStream): Pair<String, ByteArray> {
        val size = input.readUnsignedShort()
        if (size !in 1..MAX_ID_BYTES) throw DeviceSyncProtocolException("Bad guest id length $size")
        val id = ByteArray(size).also(input::readFully).decodeToString()
        return id to ByteArray(NONCE_SIZE).also(input::readFully)
    }

    fun randomBytes(random: SecureRandom, size: Int) = ByteArray(size).also(random::nextBytes)

    /**
     * 32 bytes for a fresh [SecureChannel]: HMAC-SHA256 keyed with the reconnect key over a label, the guest id and
     * both nonces. Fresh nonces from both sides make every reconnect's channel keys new, so records of an earlier
     * connection never verify on this one.
     */
    fun reconnectKeyingMaterial(key: ByteArray, guestId: String, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        val id = guestId.encodeToByteArray()
        mac.update(RECONNECT_LABEL, 0, RECONNECT_LABEL.size)
        mac.update(byteArrayOf((id.size ushr 8).toByte(), id.size.toByte()), 0, 2)
        mac.update(id, 0, id.size)
        mac.update(clientNonce, 0, clientNonce.size)
        mac.update(serverNonce, 0, serverNonce.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    /**
     * The single writer of one connection: sends [outbox] frames in order; a write that takes longer than
     * [sendTimeoutMs] closes [socket] (a peer that stopped reading cannot hold the writer). When [outbox] is closed
     * and drained it half-closes ([Socket.shutdownOutput]) so the peer reads everything up to the last frame, and
     * gives the peer [sendTimeoutMs] to close its end; closing at once with unread peer data (pings) in the
     * receive buffer would send a reset that can destroy the last frame. The caller's reader ends the linger by
     * cancelling this coroutine when it sees the peer close. Always closes [socket] at the end.
     */
    suspend fun drain(outbox: ReceiveChannel<JamFrame>, channel: SecureChannel, socket: Socket, sendTimeoutMs: Long) =
        coroutineScope {
            try {
                for (frame in outbox) {
                    val watchdog = launch {
                        delay(sendTimeoutMs)
                        runCatching { socket.close() }
                    }
                    try {
                        send(channel, frame)
                    } finally {
                        watchdog.cancel()
                    }
                }
                socket.shutdownOutput()
                delay(sendTimeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Broken or too slow; the reader sees the closed socket.
            } finally {
                runCatching { socket.close() }
            }
        }

    /** Blocking; one caller at a time per channel (each side has a single writer). */
    fun send(channel: SecureChannel, frame: JamFrame) {
        val bytes = JamFrameCodec.encode(frame)
        var offset = 0
        while (true) {
            val n = minOf(FrameCodec.CHUNK, bytes.size - offset)
            channel.send(Frame.Chunk(bytes.copyOfRange(offset, offset + n)))
            offset += n
            if (n < FrameCodec.CHUNK) return
        }
    }

    /** Blocking; reassembles one Jam frame. */
    fun receive(channel: SecureChannel): JamFrame {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val chunk = channel.receive() as? Frame.Chunk ?: throw DeviceSyncProtocolException("Expected a Jam chunk")
            buffer.write(chunk.bytes)
            if (buffer.size() > JamFrameCodec.HEADER_SIZE + JamFrameCodec.MAX_CONTROL) {
                throw DeviceSyncProtocolException("Jam frame too large")
            }
            if (chunk.bytes.size < FrameCodec.CHUNK) break
        }
        val bytes = buffer.toByteArray()
        val (type, length) = JamFrameCodec.decodeHeader(bytes)
        if (length != bytes.size - JamFrameCodec.HEADER_SIZE) throw DeviceSyncProtocolException("Jam frame length mismatch")
        return JamFrameCodec.decode(type, bytes.copyOfRange(JamFrameCodec.HEADER_SIZE, bytes.size))
    }

    fun String.hexToBytes(): ByteArray {
        if (length % 2 != 0) throw DeviceSyncProtocolException("Bad hex")
        return ByteArray(length / 2) { i ->
            val hi = Character.digit(this[2 * i], 16)
            val lo = Character.digit(this[2 * i + 1], 16)
            if (hi < 0 || lo < 0) throw DeviceSyncProtocolException("Bad hex")
            ((hi shl 4) or lo).toByte()
        }
    }
}
