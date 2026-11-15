package de.yurtemre.sonora.shared.devicesync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SecureChannelTest {

    private class Side(val socket: Socket) {
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())
    }

    /** Runs [sender] and [receiver] concurrently over a loopback TCP connection. */
    private fun <S, R> overLoopback(sender: (Side) -> S, receiver: (Side) -> R): Pair<Result<S>, Result<R>> =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            runBlocking {
                val s = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        socket.soTimeout = 20_000
                        runCatching { sender(Side(socket)) }
                    }
                }
                val r = async(Dispatchers.IO) {
                    Socket(server.inetAddress, server.localPort).use { socket ->
                        socket.soTimeout = 20_000
                        runCatching { receiver(Side(socket)) }
                    }
                }
                s.await() to r.await()
            }
        }

    @Test fun rightCodeDerivesEqualKeysAndExchangesFrames() {
        val hello = Frame.Hello(Hello(protocol = 1, deviceId = "rx", deviceName = "Receiver phone"))
        val chunk = Frame.Chunk(ByteArray(FrameCodec.CHUNK) { it.toByte() })
        val (s, r) = overLoopback(
            sender = { side ->
                val key = JpakeHandshake.run(side.input, side.output, "042917", Role.Sender, "tx")
                val channel = SecureChannel(side.input, side.output, key, Role.Sender)
                val got = channel.receive()
                channel.send(chunk)
                channel.send(Frame.Done)
                key to got
            },
            receiver = { side ->
                val key = JpakeHandshake.run(side.input, side.output, "042917", Role.Receiver, "rx")
                val channel = SecureChannel(side.input, side.output, key, Role.Receiver)
                channel.send(hello)
                key to listOf(channel.receive(), channel.receive())
            },
        )
        val (senderKey, senderGot) = s.getOrThrow()
        val (receiverKey, receiverGot) = r.getOrThrow()
        assertEquals(32, senderKey.size)
        assertContentEquals(senderKey, receiverKey)
        assertEquals(hello, senderGot)
        assertEquals(listOf(chunk, Frame.Done), receiverGot)
    }

    @Test fun wrongCodeFailsOnBothSides() {
        val (s, r) = overLoopback(
            sender = { side -> JpakeHandshake.run(side.input, side.output, "123456", Role.Sender, "tx") },
            receiver = { side -> JpakeHandshake.run(side.input, side.output, "123457", Role.Receiver, "rx") },
        )
        assertIs<DeviceSyncAuthException>(s.exceptionOrNull(), "sender: $s")
        assertIs<DeviceSyncAuthException>(r.exceptionOrNull(), "receiver: $r")
        assertEquals("code_mismatch", s.exceptionOrNull()?.message)
        assertEquals("code_mismatch", r.exceptionOrNull()?.message)
    }

    @Test fun peerClosingDuringHandshakeIsAnAuthFailure() {
        val (_, r) = overLoopback(
            sender = { side -> side.socket.close() },
            receiver = { side -> JpakeHandshake.run(side.input, side.output, "123456", Role.Receiver, "rx") },
        )
        assertIs<DeviceSyncAuthException>(r.exceptionOrNull(), "receiver: $r")
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
