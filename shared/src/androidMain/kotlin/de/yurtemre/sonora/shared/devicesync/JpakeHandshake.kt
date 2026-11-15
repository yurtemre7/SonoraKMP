package de.yurtemre.sonora.shared.devicesync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom
import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import org.bouncycastle.crypto.digests.SHA256Digest

/** Pairing failed a check: the peer used a different code, or sent an invalid proof. Never carries the code. */
class DeviceSyncAuthException(message: String) : Exception(message)

/** Which end of a device sync session this phone is. The sender is the TCP server. */
enum class Role {
    Sender, Receiver;

    /** J-PAKE participant id; the two roles always differ, so each side knows the peer's id. */
    internal val participantId: String get() = name.lowercase()
    internal val peer: Role get() = if (this == Sender) Receiver else Sender
}

/**
 * Three-round J-PAKE over [JPAKEPrimeOrderGroups.NIST_3072] with SHA-256, using the pairing code as the password,
 * followed by the round-3 key confirmation (DSYNC-03).
 *
 * Rounds 1 and 2: both sides write their payload, then read the peer's (needs a transport that buffers a few KiB,
 * as TCP does). Round 3 is ordered: the receiver sends its MAC first; the sender checks it and only then sends its
 * own MAC, so the sender never hands a connecting peer something it could test a guessed code against without
 * that peer first proving the code. A side whose check fails sends a reject message (a round with zero values)
 * before closing, so the other side also reports the mismatch.
 */
object JpakeHandshake {
    /** Upper bound for one round's payload; NIST_3072 payloads are about 2.4 KiB. */
    private const val MAX_PAYLOAD = 16 * 1024

    /**
     * Runs the handshake and returns 32 bytes of keying material (SHA-256 of the J-PAKE key).
     *
     * [myId] is informational only (this device's label for the caller); it is not sent and not authenticated.
     * J-PAKE uses the [role] names as participant ids.
     *
     * Outcomes, so callers can count failed attempts correctly (DSYNC-01: only [DeviceSyncAuthException] counts):
     * - [DeviceSyncAuthException] (`code_mismatch`): a check failed. A round-1/2 proof or the peer's round-3 MAC
     *   did not validate here, or the peer sent a reject because ours did not validate there. [output] is closed.
     * - [DeviceSyncProtocolException]: the peer sent bytes that are not a well-formed handshake message, or (message
     *   `too_many_attempts`) the sender refuses pairing during its cool-down ([refuseLocked]).
     * - [IOException] (EOF, reset, timeout): the connection failed before a mismatch was established. This is not
     *   a wrong code: a network blip or a port scan must not burn an attempt.
     */
    @Suppress("UNUSED_PARAMETER")
    fun run(input: DataInputStream, output: DataOutputStream, code: String, role: Role, myId: String): ByteArray {
        val password = code.toCharArray()
        val me = JPAKEParticipant(role.participantId, password, JPAKEPrimeOrderGroups.NIST_3072, SHA256Digest(), SecureRandom())
        password.fill('\u0000')
        val peerId = role.peer.participantId
        try {
            val r1 = me.createRound1PayloadToSend()
            writeRound(output, r1.gx1, r1.gx2, *r1.knowledgeProofForX1, *r1.knowledgeProofForX2)
            val p1 = readRound(input, 6)
            me.validateRound1PayloadReceived(JPAKERound1Payload(peerId, p1[0], p1[1], arrayOf(p1[2], p1[3]), arrayOf(p1[4], p1[5])))

            val r2 = me.createRound2PayloadToSend()
            writeRound(output, r2.a, *r2.knowledgeProofForX2s)
            val p2 = readRound(input, 3)
            me.validateRound2PayloadReceived(JPAKERound2Payload(peerId, p2[0], arrayOf(p2[1], p2[2])))

            val keyingMaterial = me.calculateKeyingMaterial()
            val myMac = me.createRound3PayloadToSend(keyingMaterial).macTag
            if (role == Role.Receiver) writeRound(output, myMac)
            val p3 = readRound(input, 1)
            me.validateRound3PayloadReceived(JPAKERound3Payload(peerId, p3[0]), keyingMaterial)
            if (role == Role.Sender) writeRound(output, myMac)

            val digest = SHA256Digest()
            val bytes = keyingMaterial.toByteArray()
            digest.update(bytes, 0, bytes.size)
            return ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
        } catch (e: CryptoException) {
            runCatching { writeRound(output) } // reject: tell the peer its proof or MAC did not validate
            runCatching { output.close() }
            throw DeviceSyncAuthException("code_mismatch")
        } catch (e: PeerRejected) {
            runCatching { output.close() }
            throw DeviceSyncAuthException("code_mismatch")
        }
    }

    /**
     * Sender during its cool-down (too many wrong codes): reads the receiver's round 1 (so closing does not reset
     * the connection before the receiver has read the answer), answers with a "locked" message and closes. Errors
     * are ignored: the connection ends either way.
     */
    fun refuseLocked(input: DataInputStream, output: DataOutputStream) {
        runCatching {
            readRound(input, 6)
            output.writeInt(4)
            output.writeInt(LOCKED)
            output.flush()
        }
        runCatching { output.close() }
    }

    /** Round value count of the "locked" message ([refuseLocked]). */
    private const val LOCKED = -1

    /** The peer sent a reject message. */
    private class PeerRejected : Exception()

    /**
     * One round = `[len:int][count:int]` then `count` × `[len:int][two's-complement big-endian bytes]`.
     * `count = 0` is a reject.
     */
    internal fun writeRound(output: DataOutputStream, vararg values: BigInteger) {
        val encoded = values.map(BigInteger::toByteArray)
        output.writeInt(4 + encoded.sumOf { 4 + it.size })
        output.writeInt(encoded.size)
        encoded.forEach { output.writeInt(it.size); output.write(it) }
        output.flush()
    }

    internal fun readRound(input: DataInputStream, count: Int): List<BigInteger> {
        val length = input.readInt()
        if (length !in 4..MAX_PAYLOAD) throw DeviceSyncProtocolException("Bad handshake payload length $length")
        val payload = ByteBuffer.wrap(ByteArray(length).also(input::readFully))
        val received = payload.int
        if (received == 0 && length == 4) throw PeerRejected()
        if (received == LOCKED && length == 4) throw DeviceSyncProtocolException(DeviceSyncServer.ERROR_TOO_MANY_ATTEMPTS)
        if (received != count) throw DeviceSyncProtocolException("Bad handshake value count $received")
        val values = List(count) {
            if (payload.remaining() < 4) throw DeviceSyncProtocolException("Truncated handshake payload")
            val size = payload.int
            if (size !in 1..payload.remaining()) throw DeviceSyncProtocolException("Bad handshake value length $size")
            BigInteger(ByteArray(size).also(payload::get))
        }
        if (payload.hasRemaining()) throw DeviceSyncProtocolException("Trailing handshake bytes")
        return values
    }
}
