package de.yurtemre.sonora.shared.devicesync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.digests.SHA256Digest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SecureChannelTest {

    /** One end of a loopback connection; [written] records every byte this side sent. */
    private class Side(val socket: Socket) {
        val written = ByteArrayOutputStream()
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(TeeOutputStream(socket.getOutputStream(), written).buffered())

        /** Value counts of the handshake rounds this side wrote (0 = reject). */
        fun roundCounts(): List<Int> {
            val data = DataInputStream(ByteArrayInputStream(written.toByteArray()))
            val counts = mutableListOf<Int>()
            while (data.available() > 0) {
                val len = data.readInt()
                counts += data.readInt()
                data.skipBytes(len - 4)
            }
            return counts
        }
    }

    private class TeeOutputStream(private val out: OutputStream, private val copy: OutputStream) : OutputStream() {
        override fun write(b: Int) { out.write(b); copy.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); copy.write(b, off, len) }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    /**
     * Runs [sender] (the server side) and [receiver] concurrently over a loopback TCP connection.
     * Each callback gets its [Side]; the results come back with both sides for inspection.
     */
    private fun <S, R> overLoopback(
        sender: (Side) -> S,
        receiver: (Side) -> R,
        senderTimeoutMs: Int = 20_000,
    ): Triple<Result<S>, Result<R>, Pair<Side, Side>> =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            runBlocking {
                val s = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        socket.soTimeout = senderTimeoutMs
                        val side = Side(socket)
                        runCatching { sender(side) } to side
                    }
                }
                val r = async(Dispatchers.IO) {
                    Socket(server.inetAddress, server.localPort).use { socket ->
                        socket.soTimeout = 20_000
                        val side = Side(socket)
                        runCatching { receiver(side) } to side
                    }
                }
                val (sr, ss) = s.await()
                val (rr, rs) = r.await()
                Triple(sr, rr, ss to rs)
            }
        }

    /** A scripted receiver that completes J-PAKE rounds 1 and 2 honestly with [code], then stops. */
    private fun receiverRounds1And2(side: Side, code: String) {
        val me = JPAKEParticipant("receiver", code.toCharArray(), JPAKEPrimeOrderGroups.NIST_3072, SHA256Digest(), SecureRandom())
        val r1 = me.createRound1PayloadToSend()
        JpakeHandshake.writeRound(side.output, r1.gx1, r1.gx2, *r1.knowledgeProofForX1, *r1.knowledgeProofForX2)
        val p1 = JpakeHandshake.readRound(side.input, 6)
        me.validateRound1PayloadReceived(JPAKERound1Payload("sender", p1[0], p1[1], arrayOf(p1[2], p1[3]), arrayOf(p1[4], p1[5])))
        val r2 = me.createRound2PayloadToSend()
        JpakeHandshake.writeRound(side.output, r2.a, *r2.knowledgeProofForX2s)
        val p2 = JpakeHandshake.readRound(side.input, 3)
        me.validateRound2PayloadReceived(JPAKERound2Payload("sender", p2[0], arrayOf(p2[1], p2[2])))
    }

    /** Hangs up either cleanly (FIN → EOF on the peer) or abortively (RST → connection reset on the peer). */
    private fun hangUp(side: Side, reset: Boolean) {
        if (reset) side.socket.setSoLinger(true, 0)
        side.socket.close()
    }

    private fun senderHandshake(side: Side) = JpakeHandshake.run(side.input, side.output, "123456", Role.Sender, "tx")

    private fun assertConnectionFailure(result: Result<*>) {
        val e = result.exceptionOrNull()
        assertIs<IOException>(e, "expected a plain connection failure, got $result")
    }

    @Test fun rightCodeDerivesEqualKeysAndExchangesFrames() {
        val hello = Frame.Hello(Hello(protocol = 1, deviceId = "rx", deviceName = "Receiver phone"))
        val chunk = Frame.Chunk(ByteArray(FrameCodec.CHUNK) { it.toByte() })
        val (s, r) = overLoopback(
            sender = { side ->
                val key = JpakeHandshake.run(side.input, side.output, "042917", Role.Sender, "tx")
                val counts = side.roundCounts()
                val channel = SecureChannel(side.input, side.output, key, Role.Sender)
                val got = channel.receive()
                channel.send(chunk)
                channel.send(Frame.Done)
                Triple(key, got, counts)
            },
            receiver = { side ->
                val key = JpakeHandshake.run(side.input, side.output, "042917", Role.Receiver, "rx")
                val counts = side.roundCounts()
                val channel = SecureChannel(side.input, side.output, key, Role.Receiver)
                channel.send(hello)
                Triple(key, listOf(channel.receive(), channel.receive()), counts)
            },
        )
        val (senderKey, senderGot, senderRounds) = s.getOrThrow()
        val (receiverKey, receiverGot, receiverRounds) = r.getOrThrow()
        assertEquals(32, senderKey.size)
        assertContentEquals(senderKey, receiverKey)
        assertEquals(hello, senderGot)
        assertEquals(listOf(chunk, Frame.Done), receiverGot)
        assertEquals(listOf(6, 3, 1), senderRounds)
        assertEquals(listOf(6, 3, 1), receiverRounds)
    }

    @Test fun wrongCodeFailsOnBothSidesAndSenderNeverRevealsItsMac() {
        val (s, r, sides) = overLoopback(
            sender = { side -> JpakeHandshake.run(side.input, side.output, "123456", Role.Sender, "tx") },
            receiver = { side -> JpakeHandshake.run(side.input, side.output, "123457", Role.Receiver, "rx") },
        )
        assertIs<DeviceSyncAuthException>(s.exceptionOrNull(), "sender: $s")
        assertIs<DeviceSyncAuthException>(r.exceptionOrNull(), "receiver: $r")
        assertEquals("code_mismatch", s.exceptionOrNull()?.message)
        assertEquals("code_mismatch", r.exceptionOrNull()?.message)
        // Sender: rounds 1 and 2, then a reject; never its round-3 MAC.
        assertEquals(listOf(6, 3, 0), sides.first.roundCounts())
        assertEquals(listOf(6, 3, 1), sides.second.roundCounts())
    }

    @Test fun peerHangingUpAfterRound2IsAConnectionFailureNotAMismatch() {
        for (reset in listOf(false, true)) {
            val (s, _, sides) = overLoopback(
                sender = ::senderHandshake,
                receiver = { side -> receiverRounds1And2(side, "000000"); hangUp(side, reset) },
            )
            assertConnectionFailure(s)
            assertEquals(listOf(6, 3), sides.first.roundCounts(), "reset=$reset")
        }
    }

    @Test fun peerStallingAfterRound2TimesOutAsAConnectionFailure() {
        val (s, _, sides) = overLoopback(
            sender = ::senderHandshake,
            receiver = { side -> receiverRounds1And2(side, "000000"); side.input.read() }, // waits for the sender to close
            senderTimeoutMs = 1_500, // well above the ~0.1 s a round takes, so only the stalled round 3 times out
        )
        assertIs<SocketTimeoutException>(s.exceptionOrNull(), "sender: $s")
        assertEquals(listOf(6, 3), sides.first.roundCounts())
    }

    @Test fun peerHangingUpDuringRound1IsAConnectionFailure() {
        for (reset in listOf(false, true)) {
            val (s, _) = overLoopback(sender = ::senderHandshake, receiver = { side -> hangUp(side, reset) })
            assertConnectionFailure(s)
        }
    }

    @Test fun malformedHandshakePayloadIsAProtocolError() {
        val truncatedValue = byteArrayOf(0, 0, 0, 12, 0, 0, 0, 6, 0, 0, 0, 100, 1, 2, 3, 4) // value claims 100 bytes, has 4
        val missingValues = byteArrayOf(0, 0, 0, 12, 0, 0, 0, 6, 0, 0, 0, 4, 1, 2, 3, 4) // 1 of 6 values present
        for (bytes in listOf(truncatedValue, missingValues)) {
            val (s, _) = overLoopback(
                sender = ::senderHandshake,
                receiver = { side -> side.output.write(bytes); side.output.flush(); side.input.skip(Long.MAX_VALUE) },
            )
            assertIs<DeviceSyncProtocolException>(s.exceptionOrNull(), "sender: $s")
        }
    }

    // --- Record layer, with fixed keying material (no handshake needed) ---

    private val keyingMaterial = ByteArray(32) { (it * 7 + 3).toByte() }

    /** Encrypts [frames] as the sender would and returns the raw records, one byte array per record. */
    private fun senderRecords(vararg frames: Frame, role: Role = Role.Sender): List<ByteArray> {
        val wire = ByteArrayOutputStream()
        val channel = SecureChannel(DataInputStream(ByteArrayInputStream(ByteArray(0))), DataOutputStream(wire), keyingMaterial, role)
        frames.forEach(channel::send)
        val input = DataInputStream(ByteArrayInputStream(wire.toByteArray()))
        return frames.map {
            val len = input.readInt()
            intBytes(len) + ByteArray(len).also(input::readFully)
        }
    }

    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun receiverOf(vararg records: ByteArray): SecureChannel {
        val bytes = records.fold(ByteArray(0)) { acc, r -> acc + r }
        return SecureChannel(DataInputStream(ByteArrayInputStream(bytes)), DataOutputStream(ByteArrayOutputStream()), keyingMaterial, Role.Receiver)
    }

    private val a = Frame.Error(ErrorFrame("e1", "first"))
    private val b = Frame.Error(ErrorFrame("e2", "second"))

    @Test fun recordsRoundTripInOrder() {
        val (ra, rb) = senderRecords(a, b)
        val channel = receiverOf(ra, rb)
        assertEquals(a, channel.receive())
        assertEquals(b, channel.receive())
    }

    @Test fun flippedCiphertextByteFails() {
        val (ra) = senderRecords(a)
        for (i in 4 until ra.size) {
            val tampered = ra.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFailsWith<DeviceSyncAuthException>("byte $i") { receiverOf(tampered).receive() }
        }
    }

    @Test fun reorderedRecordFails() {
        val (ra, rb) = senderRecords(a, b)
        assertFailsWith<DeviceSyncAuthException> { receiverOf(rb, ra).receive() }
    }

    @Test fun replayedRecordFails() {
        val (ra) = senderRecords(a)
        val channel = receiverOf(ra, ra)
        assertEquals(a, channel.receive())
        assertFailsWith<DeviceSyncAuthException> { channel.receive() }
    }

    @Test fun reflectedRecordFails() {
        // A record the receiver itself wrote (c2s) must not be accepted as an s2c record.
        val (own) = senderRecords(a, role = Role.Receiver)
        assertFailsWith<DeviceSyncAuthException> { receiverOf(own).receive() }
    }

    @Test fun channelIsUnusableAfterABadRecordLength() {
        val (valid) = senderRecords(a)
        val channel = receiverOf(intBytes(-1), valid)
        assertFailsWith<DeviceSyncProtocolException> { channel.receive() }
        assertFailsWith<DeviceSyncProtocolException> { channel.receive() } // refuses even though a valid record follows
        assertFailsWith<DeviceSyncProtocolException> { channel.send(Frame.Done) }
    }

    @Test fun oversizedRecordLengthIsRejected() {
        val bogus = intBytes(FrameCodec.HEADER_SIZE + FrameCodec.MAX_CONTROL + 17)
        assertFailsWith<DeviceSyncProtocolException> { receiverOf(bogus).receive() }
        assertFailsWith<DeviceSyncProtocolException> { receiverOf(intBytes(-1)).receive() }
    }

    @Test fun pairingCodeIsAlwaysSixDigits() {
        val random = SecureRandom()
        repeat(10_000) {
            val code = PairingCode.generate(random)
            assertTrue(code.length == 6 && code.all { it in '0'..'9' }, code)
        }
        // A source that yields 0 still gives a zero-padded code.
        val zero = object : SecureRandom() {
            override fun nextInt(bound: Int) = 0
            override fun nextBytes(bytes: ByteArray) = bytes.fill(0)
        }
        assertEquals("000000", PairingCode.generate(zero))
        val seen = (1..2_000).map { PairingCode.generate(random) }.toSet()
        assertTrue(seen.size > 1_900, "codes should be spread out, got ${seen.size} distinct")
    }
}
