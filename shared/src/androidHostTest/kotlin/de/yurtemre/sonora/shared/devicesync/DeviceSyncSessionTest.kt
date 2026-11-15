package de.yurtemre.sonora.shared.devicesync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DeviceSyncSessionTest {

    private class FakeClock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke() = now
    }

    private class MemorySource(private val data: Map<Int, ByteArray>) : SyncFileSource {
        val opened: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        override fun open(file: ManifestFile): InputStream {
            opened += file.id
            return ByteArrayInputStream(data.getValue(file.id))
        }
    }

    private class MemorySink : SyncFileSink {
        val inProgress = mutableMapOf<Int, ByteArrayOutputStream>()
        val committed = mutableMapOf<Int, ByteArray>()
        val committedHashes = mutableMapOf<Int, String>()
        val aborted = mutableListOf<Int>()
        @Volatile var bytesWritten = 0L

        override fun begin(file: ManifestFile): OutputStream {
            val buffer = ByteArrayOutputStream()
            inProgress[file.id] = buffer
            return object : OutputStream() {
                override fun write(b: Int) { buffer.write(b); bytesWritten++ }
                override fun write(b: ByteArray, off: Int, len: Int) { buffer.write(b, off, len); bytesWritten += len }
            }
        }

        override fun commit(file: ManifestFile, sha256Hex: String) {
            committed[file.id] = inProgress.remove(file.id)!!.toByteArray()
            committedHashes[file.id] = sha256Hex
        }

        override fun abort(file: ManifestFile) {
            inProgress.remove(file.id)
            aborted += file.id
        }
    }

    private val big = Random(7).nextBytes(200 * 1024)
    private val small = Random(8).nextBytes(1000)
    private val data = mapOf(1 to ByteArray(0), 2 to big, 3 to small)

    private fun manifest(protocol: Int = 1, sizes: Map<Int, Long> = emptyMap()) = Manifest(
        protocol = protocol,
        deviceId = "sender-id",
        deviceName = "Sender phone",
        bundle = SyncBundle("{}", SyncStats()),
        files = data.map { (id, bytes) ->
            ManifestFile(id, "Artist/file$id.mp3", sizes[id] ?: bytes.size.toLong(), 0L, FileKind.Audio)
        },
    )

    private val hello = Hello(protocol = 1, deviceId = "receiver-id", deviceName = "Receiver phone")
    private val loopback = InetAddress.getLoopbackAddress().hostAddress!!
    private val servers = mutableListOf<DeviceSyncServer>()

    private fun server(
        manifest: Manifest = manifest(),
        source: SyncFileSource = MemorySource(data),
        clock: () -> Long = FakeClock(),
    ): Pair<DeviceSyncServer, Int> {
        val server = DeviceSyncServer({ manifest }, source, clock)
        servers += server
        return server to server.start()
    }

    @AfterTest
    fun stopServers() = servers.forEach { it.stop() }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private fun wrong(code: String) = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The Global Constraints fingerprint, written out independently of [Fingerprint]. */
    private fun expectedFingerprint(bytes: ByteArray): String {
        val window = minOf(64 * 1024, bytes.size)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
        digest.update(bytes, 0, window)
        digest.update(bytes, bytes.size - window, window)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** A wrong-code attempt; retries while the server still holds the previous connection (closed before pairing). */
    private suspend fun attemptWrongCode(server: DeviceSyncServer, port: Int) {
        while (true) {
            try {
                DeviceSyncClient().connect(loopback, port, wrong(server.code.value), hello)
                error("Pairing with a wrong code succeeded")
            } catch (e: DeviceSyncAuthException) {
                return
            } catch (e: IOException) {
                delay(20)
            }
        }
    }

    /** Connects once the server is pairing on the connection (its round 1 arrived), runs [then], disconnects. */
    private suspend fun rawPairingConnection(port: Int, then: (Socket) -> Unit) {
        while (true) {
            val socket = Socket(loopback, port)
            try {
                JpakeHandshake.readRound(DataInputStream(socket.getInputStream()), 6)
            } catch (e: IOException) {
                socket.close() // still busy with the previous connection: closed before pairing
                delay(20)
                continue
            }
            socket.use(then)
            return
        }
    }

    @Test
    fun fullSessionTransfersEveryFileAndEndsWithDone() = test {
        val (server, port) = server()
        val firstCode = server.code.value
        val client = DeviceSyncClient()
        val received = client.connect(loopback, port, firstCode, hello)
        assertEquals(manifest(), received)
        assertEquals(ServerState.Paired("Receiver phone"), server.state.value)

        val sink = MemorySink()
        val progress = mutableListOf<TransferProgress>()
        val result = client.fetch(listOf(1, 2, 3), sink) { progress += it }

        assertEquals(listOf(1, 2, 3), result.committed)
        assertEquals(emptyMap(), result.failed)
        data.forEach { (id, bytes) ->
            assertContentEquals(bytes, sink.committed[id])
            assertEquals(sha256Hex(bytes), sink.committedHashes[id])
        }
        val last = progress.last()
        assertEquals(big.size + small.size.toLong(), last.totalBytes)
        assertEquals(last.totalSize, last.totalBytes)
        assertEquals(3, last.filesDone)
        assertEquals(3, last.filesTotal)

        client.close()
        server.state.first { it == ServerState.Done }
        assertNotEquals(firstCode, server.code.value, "a completed session leaves a fresh code for the next phone")
    }

    @Test
    fun fingerprintsFollowTheSharedFormulaForManifestIdsOnly() = test {
        val (server, port) = server()
        val client = DeviceSyncClient()
        client.connect(loopback, port, server.code.value, hello)

        val fingerprints = client.fingerprints(listOf(1, 2, 3, 99))

        assertEquals(setOf(1, 2, 3), fingerprints.keys)
        data.forEach { (id, bytes) ->
            assertEquals(expectedFingerprint(bytes), fingerprints[id])
            val window = minOf(64 * 1024, bytes.size)
            val shared = Fingerprint.compute(bytes.size.toLong(), bytes.copyOf(window), bytes.copyOfRange(bytes.size - window, bytes.size))
            assertEquals(expectedFingerprint(bytes), shared)
        }
        client.close()
    }

    @Test
    fun thirdWrongCodeRotatesTheCode() = test {
        val clock = FakeClock()
        val (server, port) = server(clock = clock)
        val original = server.code.value
        val originalExpiry = server.codeExpiresAtMs.value
        assertEquals(clock.now + 5 * 60_000, originalExpiry)

        repeat(2) { attemptWrongCode(server, port) }
        assertEquals(original, server.code.value)
        server.state.first { it == ServerState.Failed("code_mismatch") }

        clock.now += 1_000
        attemptWrongCode(server, port)
        assertNotEquals(original, server.code.value)
        assertEquals(clock.now + 5 * 60_000, server.codeExpiresAtMs.value)

        // The new code works.
        val client = DeviceSyncClient()
        client.connect(loopback, port, server.code.value, hello)
        client.close()
    }

    @Test
    fun disconnectOrGarbageDuringPairingDoesNotCountAsAnAttempt() = test {
        val (server, port) = server()
        val original = server.code.value
        repeat(2) { rawPairingConnection(port) {} } // disconnect mid-handshake
        rawPairingConnection(port) { // malformed round
            DataOutputStream(it.getOutputStream()).apply { writeInt(1); flush() }
        }
        repeat(2) { attemptWrongCode(server, port) }
        assertEquals(original, server.code.value, "disconnects and malformed data must not burn attempts")
        attemptWrongCode(server, port)
        assertNotEquals(original, server.code.value)
    }

    @Test
    fun codeExpiresAfterFiveMinutesEvenWithoutConnections() = test {
        val clock = FakeClock()
        val (server, _) = server(clock = clock)
        val original = server.code.value
        clock.now += 5 * 60_000 - 1
        delay(1_200)
        assertEquals(original, server.code.value)

        clock.now += 1
        val rotated = server.code.first { it != original }
        assertNotEquals(original, rotated)
        assertEquals(clock.now + 5 * 60_000, server.codeExpiresAtMs.value)
    }

    @Test
    fun secondConcurrentReceiverIsRejected() = test {
        val (server, port) = server()
        val first = DeviceSyncClient()
        first.connect(loopback, port, server.code.value, hello)

        assertFailsWith<IOException> { DeviceSyncClient().connect(loopback, port, server.code.value, hello) }

        val sink = MemorySink()
        assertEquals(listOf(3), first.fetch(listOf(3), sink) {}.committed)
        first.close()
    }

    @Test
    fun receiverWithAnotherProtocolVersionIsRefused() = test {
        val (server, port) = server()
        val e = assertFailsWith<DeviceSyncProtocolException> {
            DeviceSyncClient().connect(loopback, port, server.code.value, hello.copy(protocol = 2))
        }
        assertEquals("version_mismatch", e.message)
    }

    @Test
    fun senderWithAnotherProtocolVersionIsRefused() = test {
        val (server, port) = server(manifest = manifest(protocol = 2))
        val e = assertFailsWith<DeviceSyncProtocolException> {
            DeviceSyncClient().connect(loopback, port, server.code.value, hello)
        }
        assertEquals("version_mismatch", e.message)
    }

    @Test
    fun sizeMismatchAbortsOnlyThatFile() = test {
        val source = MemorySource(data + (3 to small.copyOf(small.size - 1)))
        val (server, port) = server(source = source)
        val client = DeviceSyncClient()
        client.connect(loopback, port, server.code.value, hello)
        val sink = MemorySink()

        val result = client.fetch(listOf(3, 2), sink) {}

        assertEquals(listOf(2), result.committed)
        assertEquals(mapOf(3 to "size_mismatch"), result.failed)
        assertEquals(listOf(3), sink.aborted)
        assertContentEquals(big, sink.committed[2])
        client.close()
    }

    @Test
    fun checksumMismatchAbortsOnlyThatFile() = test {
        val port = fakeSender { channel ->
            channel.receive() as Frame.FetchFrame
            channel.send(Frame.FileStartFrame(FileStart(3, small.size.toLong())))
            channel.send(Frame.Chunk(small))
            channel.send(Frame.FileEndFrame(FileEnd(3, sha256Hex(small.reversedArray()))))
            channel.send(Frame.FileStartFrame(FileStart(1, 0)))
            channel.send(Frame.FileEndFrame(FileEnd(1, sha256Hex(ByteArray(0)))))
            channel.receive() // Done
        }
        val client = DeviceSyncClient()
        client.connect(loopback, port, FAKE_CODE, hello)
        val sink = MemorySink()

        val result = client.fetch(listOf(3, 1), sink) {}

        assertEquals(listOf(1), result.committed)
        assertEquals(mapOf(3 to "checksum_mismatch"), result.failed)
        assertEquals(listOf(3), sink.aborted)
        client.close()
    }

    @Test
    fun unknownIdIsRejectedWithoutReadingAnything() = test {
        val source = MemorySource(data)
        val (server, port) = server(source = source)
        val client = DeviceSyncClient()
        client.connect(loopback, port, server.code.value, hello)
        val sink = MemorySink()

        val result = client.fetch(listOf(99, 3), sink) {}

        assertEquals(listOf(3), result.committed)
        assertEquals(mapOf(99 to "unknown_file"), result.failed)
        assertEquals(listOf(3), source.opened.toList())
        client.close()
    }

    @Test
    fun cancellingFetchMidFileClosesTheSocketAndAbortsTheFile() = test {
        val peerClosed = CountDownLatch(1)
        val port = fakeSender { channel ->
            channel.receive() as Frame.FetchFrame
            channel.send(Frame.FileStartFrame(FileStart(2, big.size.toLong())))
            channel.send(Frame.Chunk(big.copyOf(FrameCodec.CHUNK)))
            try {
                channel.receive() // blocks until the receiver closes its socket
            } catch (e: IOException) {
                peerClosed.countDown()
            }
        }
        val client = DeviceSyncClient()
        client.connect(loopback, port, FAKE_CODE, hello)
        val sink = MemorySink()

        val fetch = async(Dispatchers.Default) { client.fetch(listOf(2), sink) {} }
        while (sink.bytesWritten < FrameCodec.CHUNK) delay(10)
        fetch.cancel()
        withTimeout(2_000) { fetch.join() }

        assertEquals(listOf(2), sink.aborted)
        assertTrue(sink.committed.isEmpty())
        assertTrue(peerClosed.await(2, TimeUnit.SECONDS), "the socket is closed on cancellation")
    }

    /**
     * A scripted sender built directly on [JpakeHandshake] and [SecureChannel] (code [FAKE_CODE]): pairs, answers
     * HELLO with [manifest], then runs [script]. Returns the port.
     */
    private fun fakeSender(script: (SecureChannel) -> Unit): Int {
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            serverSocket.use {
                it.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream().buffered())
                    val output = DataOutputStream(socket.getOutputStream().buffered())
                    val key = JpakeHandshake.run(input, output, FAKE_CODE, Role.Sender, "fake")
                    val channel = SecureChannel(input, output, key, Role.Sender)
                    channel.receive() as Frame.Hello
                    channel.send(Frame.ManifestFrame(manifest()))
                    script(channel)
                }
            }
        }
        return serverSocket.localPort
    }

    private companion object {
        const val FAKE_CODE = "246810"
    }
}
