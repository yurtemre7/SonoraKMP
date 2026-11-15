package de.yurtemre.sonora.shared.devicesync

import java.io.DataInputStream
import java.io.DataOutputStream
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.modes.GCMModeCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Authenticated encrypted device sync frames after pairing (DSYNC-03). Each [Frame] is encoded with [FrameCodec]
 * and sealed as one AES-256-GCM record `[len:int][ciphertext ‖ 16-byte tag]`. Keys and 4-byte nonce prefixes come
 * from HKDF-SHA256 over the J-PAKE keying material, one pair per direction (`c2s` receiver → sender, `s2c`
 * sender → receiver); the nonce is prefix ‖ 64-bit big-endian record counter from 0, so a replayed, reordered,
 * reflected or altered record fails its tag check → [DeviceSyncAuthException]. After any send or receive failure
 * the channel is broken: both directions then throw [DeviceSyncProtocolException].
 *
 * GCM is BouncyCastle's lightweight [GCMBlockCipher] (same as the rest of the crypto; behaves identically on every
 * Android version, no JCA provider involved). [send] and [receive] block; [send] may be called from another thread
 * than [receive].
 */
class SecureChannel(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    keyingMaterial: ByteArray,
    role: Role,
) {
    private class Direction(keyingMaterial: ByteArray, info: String) {
        val key: KeyParameter
        val prefix: ByteArray
        val cipher: GCMModeCipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        var counter = 0L

        init {
            val okm = ByteArray(KEY_SIZE + PREFIX_SIZE)
            HKDFBytesGenerator(SHA256Digest()).apply {
                init(HKDFParameters(keyingMaterial, ByteArray(0), info.encodeToByteArray()))
                generateBytes(okm, 0, okm.size)
            }
            key = KeyParameter(okm, 0, KEY_SIZE)
            prefix = okm.copyOfRange(KEY_SIZE, okm.size)
            okm.fill(0)
        }

        /** Initializes [cipher] for the next record and advances the counter. */
        fun next(encrypt: Boolean): GCMModeCipher {
            check(counter != Long.MAX_VALUE) { "Record counter exhausted" }
            val nonce = prefix.copyOf(NONCE_SIZE)
            for (i in 0 until 8) nonce[PREFIX_SIZE + i] = (counter ushr (56 - 8 * i)).toByte()
            counter++
            cipher.init(encrypt, AEADParameters(key, TAG_SIZE * 8, nonce))
            return cipher
        }
    }

    private val c2s = Direction(keyingMaterial, "sonora-sync v1 c2s")
    private val s2c = Direction(keyingMaterial, "sonora-sync v1 s2c")
    private val outgoing = if (role == Role.Sender) s2c else c2s
    private val incoming = if (role == Role.Sender) c2s else s2c
    private val sendLock = Any()
    private val receiveLock = Any()
    @Volatile private var broken = false

    fun send(frame: Frame) {
        val plain = FrameCodec.encode(frame)
        synchronized(sendLock) {
            checkUsable()
            breakOnFailure {
                val cipher = outgoing.next(encrypt = true)
                val sealed = ByteArray(cipher.getOutputSize(plain.size))
                val n = cipher.processBytes(plain, 0, plain.size, sealed, 0)
                cipher.doFinal(sealed, n)
                output.writeInt(sealed.size)
                output.write(sealed)
                output.flush()
            }
        }
    }

    fun receive(): Frame = synchronized(receiveLock) {
        checkUsable()
        breakOnFailure {
            val length = input.readInt()
            if (length !in TAG_SIZE..MAX_RECORD) throw DeviceSyncProtocolException("Bad record length $length")
            val sealed = ByteArray(length).also(input::readFully)
            val cipher = incoming.next(encrypt = false)
            val plain = ByteArray(cipher.getOutputSize(length))
            try {
                val n = cipher.processBytes(sealed, 0, length, plain, 0)
                cipher.doFinal(plain, n)
            } catch (e: InvalidCipherTextException) {
                throw DeviceSyncAuthException("record_auth_failed")
            }
            val (type, payloadLength) = FrameCodec.decodeHeader(plain)
            if (payloadLength != plain.size - FrameCodec.HEADER_SIZE) throw DeviceSyncProtocolException("Record/frame length mismatch")
            FrameCodec.decodeBody(type, plain.copyOfRange(FrameCodec.HEADER_SIZE, plain.size))
        }
    }

    private fun checkUsable() {
        if (broken) throw DeviceSyncProtocolException("Channel broken by an earlier failure")
    }

    /** Any failure leaves the stream position or the counters out of step with the peer: refuse further use. */
    private inline fun <T> breakOnFailure(block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            broken = true
            throw e
        }

    private companion object {
        const val KEY_SIZE = 32
        const val PREFIX_SIZE = 4
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        const val MAX_RECORD = FrameCodec.HEADER_SIZE + FrameCodec.MAX_CONTROL + TAG_SIZE
    }
}
