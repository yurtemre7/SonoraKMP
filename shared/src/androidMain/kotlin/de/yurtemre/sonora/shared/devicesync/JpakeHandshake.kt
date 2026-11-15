package de.yurtemre.sonora.shared.devicesync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import org.bouncycastle.crypto.digests.SHA256Digest

/** Pairing failed: the peer used a different code (or aborted the handshake). Never carries the code. */
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
 * followed by the round-3 key confirmation (DSYNC-03). Each round, both sides write their payload first and then
 * read the peer's, so it needs a transport that buffers a few KiB (a TCP socket does).
 */
object JpakeHandshake {
    /** Upper bound for one round's payload; NIST_3072 payloads are about 2.4 KiB. */
    private const val MAX_PAYLOAD = 16 * 1024

    /**
     * Runs the handshake and returns 32 bytes of keying material (SHA-256 of the J-PAKE key).
     * [myId] is only this device's label for callers; J-PAKE uses the [role] names as participant ids.
     * A wrong code, an invalid peer proof or the peer hanging up mid-handshake → [DeviceSyncAuthException]
     * (`code_mismatch`); on that path [output] is closed so the peer stops waiting too.
     */
    @Suppress("UNUSED_PARAMETER")
    fun run(input: DataInputStream, output: DataOutputStream, code: String, role: Role, myId: String): ByteArray {
        val password = code.toCharArray()
        val me = JPAKEParticipant(role.participantId, password, JPAKEPrimeOrderGroups.NIST_3072, SHA256Digest(), SecureRandom())
        password.fill('\u0000')
        val peerId = role.peer.participantId
        try {
            val r1 = me.createRound1PayloadToSend()
            send(output, r1.gx1, r1.gx2, *r1.knowledgeProofForX1, *r1.knowledgeProofForX2)
            val p1 = receive(input, 6)
            me.validateRound1PayloadReceived(JPAKERound1Payload(peerId, p1[0], p1[1], arrayOf(p1[2], p1[3]), arrayOf(p1[4], p1[5])))

            val r2 = me.createRound2PayloadToSend()
            send(output, r2.a, *r2.knowledgeProofForX2s)
            val p2 = receive(input, 3)
            me.validateRound2PayloadReceived(JPAKERound2Payload(peerId, p2[0], arrayOf(p2[1], p2[2])))

            val keyingMaterial = me.calculateKeyingMaterial()
            send(output, me.createRound3PayloadToSend(keyingMaterial).macTag)
            val p3 = receive(input, 1)
            me.validateRound3PayloadReceived(JPAKERound3Payload(peerId, p3[0]), keyingMaterial)

            val digest = SHA256Digest()
            val bytes = keyingMaterial.toByteArray()
            digest.update(bytes, 0, bytes.size)
            return ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
        } catch (e: CryptoException) {
            failAndClose(output)
        } catch (e: EOFException) {
            failAndClose(output)
        }
    }

    private fun failAndClose(output: DataOutputStream): Nothing {
        runCatching { output.close() }
        throw DeviceSyncAuthException("code_mismatch")
    }

    /** One round = `[len:int][count:int]` then `count` × `[len:int][two's-complement big-endian bytes]`. */
    private fun send(output: DataOutputStream, vararg values: BigInteger) {
        val encoded = values.map(BigInteger::toByteArray)
        output.writeInt(4 + encoded.sumOf { 4 + it.size })
        output.writeInt(encoded.size)
        encoded.forEach { output.writeInt(it.size); output.write(it) }
        output.flush()
    }

    private fun receive(input: DataInputStream, count: Int): List<BigInteger> {
        val length = input.readInt()
        if (length !in 4..MAX_PAYLOAD) throw DeviceSyncProtocolException("Bad handshake payload length $length")
        val payload = DataInputStream(ByteArray(length).also(input::readFully).inputStream())
        if (payload.readInt() != count) throw DeviceSyncProtocolException("Bad handshake payload")
        val values = List(count) {
            val size = payload.readInt()
            if (size !in 1..payload.available()) throw DeviceSyncProtocolException("Bad handshake value length")
            BigInteger(ByteArray(size).also(payload::readFully))
        }
        if (payload.available() != 0) throw DeviceSyncProtocolException("Trailing handshake bytes")
        return values
    }
}
