package de.yurtemre.sonora.shared.jam

import de.yurtemre.sonora.shared.devicesync.JpakeHandshake
import de.yurtemre.sonora.shared.devicesync.Role
import de.yurtemre.sonora.shared.devicesync.SecureChannel
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Loopback tests for [JamServer] and [JamClient]. Every wait is on an event, a frame or a state flow (never a fixed
 * sleep); time-dependent rules (lockout, reconnect window, Pong host time) use injected fake clocks.
 */
class JamServerTest {

    private class FakeClock(@Volatile var now: Long) : () -> Long {
        override fun invoke() = now
    }

    private val loopback = InetAddress.getLoopbackAddress().hostAddress!!
    private val code = "482916"
    private val hostClock = FakeClock(1_000_000L)
    private val guestClock = FakeClock(400_000L)
    private val servers = mutableListOf<JamServer>()
    private val connections = mutableListOf<JamConnection>()
    private val song = JamSong("Song", "Artist", "Album", 200_000)

    private fun server(
        sendQueueCapacity: Int = JamServer.SEND_QUEUE_CAPACITY,
        sendTimeoutMs: Long = JamServer.SEND_TIMEOUT_MS,
        pairingTimeoutMs: Int = JamServer.PAIRING_TIMEOUT_MS,
    ) = JamServer(
        hostClock,
        pairingTimeoutMs = pairingTimeoutMs,
        sendQueueCapacity = sendQueueCapacity,
        sendTimeoutMs = sendTimeoutMs,
    ).also { servers += it }

    private fun client(protocol: Int = JAM_PROTOCOL_VERSION) = JamClient(guestClock, protocol = protocol)

    private suspend fun join(port: Int, name: String, code: String = this.code, host: String = loopback): JamConnection =
        client().join(host, port, code, name).also { connections += it }

    @AfterTest
    fun tearDown() = runBlocking {
        connections.forEach { it.abort() }
        withTimeout(30_000) {
            servers.forEach {
                it.stop()
                it.awaitTermination()
            }
        }
    }

    // Generous ceiling: cold-JIT J-PAKE rounds can take seconds on a loaded runner; tests wait on events, not time.
    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(90_000) { block() } }

    private suspend inline fun <reified T : JamEvent> JamServer.await(crossinline matches: (T) -> Boolean = { true }): T =
        events.filterIsInstance<T>().first { matches(it) }

    private suspend inline fun <reified T : JamFrame> JamConnection.await(crossinline matches: (T) -> Boolean = { true }): T =
        frames.filterIsInstance<T>().first { matches(it) }

    private suspend fun expectError(error: JamError, block: suspend () -> Unit) {
        val e = assertFailsWith<JamException> { block() }
        assertEquals(error, e.error)
    }

    private fun state(index: Int, queue: List<JamSong> = listOf(song)) =
        JamStateFrame(JamState(queue, index, true, 0, hostClock.now, true, false))

    /** A guest that pairs with raw primitives and then never reads again (a stalled phone). */
    private fun rawJoin(port: Int, name: String): Pair<Socket, String> {
        val socket = Socket()
        socket.receiveBufferSize = 4 * 1024
        socket.connect(InetSocketAddress(loopback, port))
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())
        JamWire.writePreamble(output, JamWire.MODE_JOIN)
        assertEquals(JamWire.STATUS_OK, input.readUnsignedByte())
        val keyingMaterial = JpakeHandshake.run(input, output, code, Role.Receiver, "guest")
        val channel = SecureChannel(input, output, keyingMaterial, Role.Receiver)
        JamWire.send(channel, JamHello(JAM_PROTOCOL_VERSION, name))
        val welcome = JamWire.receive(channel) as JamWelcome
        return socket to welcome.guestId
    }

    @Test
    fun correctCodeJoins() = test {
        val server = server()
        val port = server.start(code)
        val guest = join(port, "Ana")
        assertEquals(JamEvent.GuestJoined(guest.guestId, "Ana"), server.await<JamEvent.GuestJoined>())
        assertEquals(guest.guestId, guest.reconnectTicket.guestId)
    }

    @Test
    fun wrongCodeThreeTimesLocksOut() = test {
        val server = server()
        val port = server.start(code)
        val dropped = join(port, "Bea")
        repeat(3) { expectError(JamError.WrongCode) { join(port, "Mallory", code = "000000") } }
        server.lockedUntilMs.first { it > hostClock.now }
        // Locked: even the right code is refused, and that does not count as an attempt.
        expectError(JamError.Locked) { join(port, "Ana") }
        // The lock is about the code: a dropped guest still gets back in with its key.
        dropped.abort()
        server.await<JamEvent.GuestLeft> { it.id == dropped.guestId }
        client().reconnect(loopback, port, dropped.reconnectTicket, "Bea").also { connections += it }
        hostClock.now = server.lockedUntilMs.value
        val guest = join(port, "Ana")
        assertEquals(guest.guestId, server.await<JamEvent.GuestJoined> { it.name == "Ana" }.id)
    }

    @Test
    fun parallelWrongCodesGetAtMostThreeGuessesPerCoolDown() = test {
        val server = server()
        val port = server.start(code)
        val errors = (1..JamServer.MAX_JOIN_HANDSHAKES).map {
            async(Dispatchers.IO) { assertFailsWith<JamException> { client().join(loopback, port, "000000", "Mallory $it") }.error }
        }.awaitAll()
        assertEquals(3, errors.count { it == JamError.WrongCode }, "counted guesses: $errors")
        assertTrue(errors.all { it == JamError.WrongCode || it == JamError.Locked }, "$errors")
    }

    @Test
    fun tenWrongCodesExhaustTheCodeUntilTheHostReplacesIt() = test {
        val server = server()
        val port = server.start(code)
        val stay = join(port, "Stay")
        var wrong = 0
        while (wrong < JamServer.MAX_WRONG_CODES) {
            val e = assertFailsWith<JamException> { join(port, "Mallory", code = "000000") }
            when (e.error) {
                JamError.WrongCode -> wrong++
                JamError.Locked -> hostClock.now = server.lockedUntilMs.value // wait out the cool-down
                else -> error("unexpected ${e.error}")
            }
        }
        server.codeExhausted.first { it }
        hostClock.now = server.lockedUntilMs.value + 1
        // Past the timed lock, the code is still dead.
        expectError(JamError.CodeExhausted) { join(port, "Ana") }

        // Reconnects do not need the code.
        stay.abort()
        server.await<JamEvent.GuestLeft> { it.id == stay.guestId }
        client().reconnect(loopback, port, stay.reconnectTicket, "Stay").also { connections += it }

        server.replaceCode("135790")
        assertFalse(server.codeExhausted.value)
        expectError(JamError.WrongCode) { join(port, "Ana") } // the old code is just a wrong one now
        val guest = join(port, "Ana", code = "135790")
        assertEquals(guest.guestId, server.await<JamEvent.GuestJoined> { it.name == "Ana" }.id)
    }

    /** Pairs-to-be that send the join preamble and a J-PAKE length, then one byte every 100 ms. */
    private fun CoroutineScope.trickler(port: Int): Socket {
        val socket = Socket(loopback, port)
        val output = DataOutputStream(socket.getOutputStream())
        JamWire.writePreamble(output, JamWire.MODE_JOIN)
        output.writeInt(1_000)
        output.flush()
        launch(Dispatchers.IO) {
            runCatching {
                while (true) {
                    output.write(0)
                    output.flush()
                    delay(100)
                }
            }
        }
        return socket
    }

    @Test
    fun slowHandshakesLoseTheirSlotAtTheDeadlineAndReconnectsKeepASlot() = test {
        val server = server(pairingTimeoutMs = 1_500)
        val port = server.start(code)
        val dropped = join(port, "Ana")
        dropped.abort()
        server.await<JamEvent.GuestLeft>()

        coroutineScope {
            val tricklers = (1..JamServer.MAX_JOIN_HANDSHAKES).map { trickler(port) }
            server.joinHandshakes.first { it == JamServer.MAX_JOIN_HANDSHAKES }
            // Joins are saturated: a newcomer is told to try again, a returning guest still gets in.
            expectError(JamError.Locked) { join(port, "Late") }
            client().reconnect(loopback, port, dropped.reconnectTicket, "Ana").also { connections += it }
            // Each trickler is closed by the total deadline although it never stops sending.
            tricklers.forEach { assertEquals(-1, withContext(Dispatchers.IO) { readOrEof(it.getInputStream()) }) }
            tricklers.forEach { it.close() }
        }
        server.joinHandshakes.first { it == 0 }
        join(port, "Late")
    }

    @Test
    fun aJoinThatReachesTheDeadlineDuringItsHandshakeIsNeverLeftRegistered() = test {
        val server = server(pairingTimeoutMs = 1_000)
        val port = server.start(code)
        val warm = join(port, "Warm-up") // warm JIT: the J-PAKE rounds below finish well inside the deadline
        // Each handshake overruns its deadline right before registration (the watchdog has closed its socket).
        server.beforeRegister = { socket -> while (!socket.isClosed) Thread.onSpinWait() }
        // Concurrent joins contend for the join slot, so some of them suspend while waiting for it.
        (1..3).map { async(Dispatchers.IO) { runCatching { client().join(loopback, port, code, "Late $it") } } }.awaitAll()
            .forEach { it.getOrNull()?.let(connections::add) }
        server.beforeRegister = null
        val guest = join(port, "On time")
        // Whatever was registered with a dead socket has been detached again; nothing is stuck in the Jam.
        withTimeout(10_000) {
            while (server.registeredGuests() != setOf(warm.guestId, guest.guestId)) delay(20)
        }
    }

    @Test
    fun aJoinPairedWithTheOldCodeIsRefusedAfterReplaceCode() = test {
        val server = server()
        val port = server.start(code)
        server.beforeRegister = { server.replaceCode("135790") } // the host replaces the code mid-handshake
        expectError(JamError.WrongCode) { join(port, "Ana") }
        server.beforeRegister = null
        assertTrue(server.registeredGuests().isEmpty())
        assertFailsWith<IllegalArgumentException> { server.replaceCode("135790") } // same code: no silent reset
        join(port, "Ana", code = "135790")
    }

    @Test
    fun broadcastRacingStopStillDeliversJamEnd() = test {
        val server = server()
        val port = server.start(code)
        val guests = (1..3).map { join(port, "Guest $it") }
        server.beforeDeliver = { server.stop() } // stop lands between the broadcast's snapshot and its sends
        server.broadcast(state(0))
        guests.forEach { assertEquals(listOf<JamFrame>(JamEnd(JamServer.REASON_HOST_ENDED)), it.frames.toList()) }
    }

    @Test
    fun aWrongReconnectKeyDoesNotBurnTheTicket() = test {
        val server = server()
        val port = server.start(code)
        val guest = join(port, "Ana")
        val ticket = guest.reconnectTicket
        guest.abort()
        server.await<JamEvent.GuestLeft>()
        val forged = JamReconnectTicket(ticket.guestId, ByteArray(JamWire.KEY_SIZE))
        expectError(JamError.ReconnectRefused) { client().reconnect(loopback, port, forged, "Eve") }
        val back = client().reconnect(loopback, port, ticket, "Ana").also { connections += it }
        assertEquals(guest.guestId, back.guestId)
    }

    @Test
    fun aGuestFloodingRequestsIsRateLimited() = test {
        val server = server()
        val port = server.start(code)
        val guest = join(port, "Ana")
        // 2× the limit: with the ping burst and the final JamEnd this still fits the client's 64-frame outbox.
        repeat(JamServer.MAX_REQUESTS_PER_SECOND * 2) { guest.send(JamAddRequest(song)) }
        guest.close()
        val events = mutableListOf<JamEvent>()
        server.events.first { events += it; it is JamEvent.GuestLeft }
        assertEquals(JamServer.MAX_REQUESTS_PER_SECOND, events.count { it is JamEvent.AddRequest })
    }

    @Test
    fun eighthGuestGetsFullButAKnownGuestMayRejoin() = test {
        val server = server()
        val port = server.start(code)
        val guests = (1..MAX_GUESTS).map { join(port, "Guest $it") }
        expectError(JamError.Full) { join(port, "Guest 8") }

        val dropped = guests.first()
        val ticket = dropped.reconnectTicket
        dropped.abort()
        server.await<JamEvent.GuestLeft> { it.id == dropped.guestId }
        // The dropped guest's slot stays reserved for its reconnect window: a newcomer is still refused…
        expectError(JamError.Full) { join(port, "Guest 8") }
        // …and the known guest gets back in.
        val back = client().reconnect(loopback, port, ticket, "Guest 1").also { connections += it }
        assertEquals(dropped.guestId, back.guestId)
    }

    @Test
    fun broadcastReachesEveryGuest() = test {
        val server = server()
        val port = server.start(code)
        val guests = (1..3).map { join(port, "Guest $it") }
        server.broadcast(state(0))
        guests.forEach { assertEquals(state(0), it.await<JamStateFrame>()) }
        server.sendTo(guests[1].guestId, state(1))
        server.broadcast(state(2))
        assertEquals(state(2), guests[0].await<JamStateFrame>())
        assertEquals(state(1), guests[1].await<JamStateFrame>())
        assertEquals(state(2), guests[1].await<JamStateFrame>())
    }

    @Test
    fun oneGuestsDisconnectDoesNotStopOthers() = test {
        val server = server()
        val port = server.start(code)
        val (a, b, c) = (1..3).map { join(port, "Guest $it") }
        a.abort()
        server.await<JamEvent.GuestLeft> { it.id == a.guestId }
        server.broadcast(state(0))
        assertEquals(state(0), b.await<JamStateFrame>())
        assertEquals(state(0), c.await<JamStateFrame>())
        b.send(JamAddRequest(song))
        assertEquals(JamEvent.AddRequest(b.guestId, song), server.await<JamEvent.AddRequest>())
    }

    @Test
    fun guestFramesMapToEvents() = test {
        val server = server()
        val port = server.start(code)
        val guest = join(port, "Ana")
        hostClock.now += 5_000
        guest.send(JamControl(JamAction.Next))
        assertEquals(JamEvent.Control(guest.guestId, JamAction.Next, hostClock.now), server.await<JamEvent.Control>())
        guest.send(JamAddRequest(song))
        assertEquals(JamEvent.AddRequest(guest.guestId, song), server.await<JamEvent.AddRequest>())
        guest.close() // a deliberate leave
        assertEquals(guest.guestId, server.await<JamEvent.GuestLeft>().id)
    }

    @Test
    fun reconnectWithinThirtySecondsIsAccepted() = test {
        val server = server()
        val port = server.start(code)
        val guest = join(port, "Ana")
        val ticket = guest.reconnectTicket
        guest.abort()
        server.await<JamEvent.GuestLeft> { it.id == guest.guestId }
        hostClock.now += JamServer.RECONNECT_WINDOW_MS - 1

        val back = client().reconnect(loopback, port, ticket, "Ana").also { connections += it }
        assertEquals(guest.guestId, back.guestId)
        assertEquals(JamEvent.GuestJoined(guest.guestId, "Ana"), server.await<JamEvent.GuestJoined> { it.id == guest.guestId })
        server.broadcast(state(0))
        assertEquals(state(0), back.await<JamStateFrame>())

        // Single use: the old ticket is spent, the guest holds a fresh one.
        expectError(JamError.ReconnectRefused) { client().reconnect(loopback, port, ticket, "Ana") }
        assertFalse(back.reconnectTicket.key.contentEquals(ticket.key))
    }

    @Test
    fun reconnectAfterThirtySecondsIsRefused() = test {
        val server = server()
        val port = server.start(code)
        val guest = join(port, "Ana")
        val ticket = guest.reconnectTicket
        guest.abort()
        server.await<JamEvent.GuestLeft> { it.id == guest.guestId }
        hostClock.now += JamServer.RECONNECT_WINDOW_MS
        expectError(JamError.ReconnectRefused) { client().reconnect(loopback, port, ticket, "Ana") }
    }

    @Test
    fun kickEndsTheGuestAndDropsItsTicket() = test {
        val server = server()
        val port = server.start(code)
        val (a, b) = (1..2).map { join(port, "Guest $it") }
        server.kick(a.guestId)
        assertEquals(JamEnd(JamServer.REASON_KICKED), a.await<JamEnd>())
        assertEquals(a.guestId, server.await<JamEvent.GuestLeft>().id)
        expectError(JamError.ReconnectRefused) { client().reconnect(loopback, port, a.reconnectTicket, "Guest 1") }
        server.broadcast(state(0))
        assertEquals(state(0), b.await<JamStateFrame>())
    }

    @Test
    fun stopSendsJamEndToAll() = test {
        val server = server()
        val port = server.start(code)
        val guests = (1..3).map { join(port, "Guest $it") }
        server.stop()
        guests.forEach { assertEquals(listOf<JamFrame>(JamEnd(JamServer.REASON_HOST_ENDED)), it.frames.toList()) }
    }

    @Test
    fun stopLeavesNoCoroutinesOrSockets() = test {
        val server = server()
        val port = server.start(code)
        val guest = join(port, "Ana")
        // A connection still in its handshake when the Jam ends.
        val pending = Socket(loopback, port)
        DataOutputStream(pending.getOutputStream()).apply { JamWire.writePreamble(this, JamWire.MODE_JOIN) }

        server.stop()
        server.awaitTermination()
        assertTrue(server.job.isCompleted)
        assertTrue(server.job.children.none())
        assertEquals(-1, withContext(Dispatchers.IO) { readOrEof(pending.getInputStream()) })
        assertFailsWith<IOException> { withContext(Dispatchers.IO) { Socket(loopback, port).close() } }

        guest.frames.toList()
        guest.awaitClosed()
        assertTrue(guest.job.isCompleted)
    }

    private fun readOrEof(input: InputStream): Int = try {
        while (input.read() != -1) Unit
        -1
    } catch (e: IOException) {
        -1
    }

    @Test
    fun differentProtocolVersionIsRefused() = test {
        val server = server()
        val port = server.start(code)
        expectError(JamError.VersionMismatch) { client(protocol = JAM_PROTOCOL_VERSION + 1).join(loopback, port, code, "Old") }
        val guest = join(port, "Ana")
        // The refused guest never joined.
        assertEquals(JamEvent.GuestJoined(guest.guestId, "Ana"), server.events.first())
    }

    @Test
    fun aStalledGuestDoesNotBlockBroadcastToOthers() = test {
        val server = server(sendQueueCapacity = 16, sendTimeoutMs = 1_000)
        val port = server.start(code)
        val good = (1..2).map { join(port, "Guest $it") }
        val (stalledSocket, stalledId) = withContext(Dispatchers.IO) { rawJoin(port, "Stalled") }
        try {
            val left = async { server.await<JamEvent.GuestLeft>() }
            val bigQueue = List(1_500) { song.copy(title = "Song $it " + "x".repeat(80)) }
            var index = 0
            // Paced by the readers that keep up; the stalled guest's buffers fill and it is dropped.
            while (!left.isCompleted && index < 2_000) {
                server.broadcast(state(index, bigQueue))
                good.forEach { assertEquals(index, it.await<JamStateFrame>().state.index) }
                index++
            }
            assertEquals(stalledId, left.await().id)
            server.broadcast(state(-1))
            good.forEach { assertEquals(-1, it.await<JamStateFrame>().state.index) }
        } finally {
            stalledSocket.close()
        }
    }

    @Test
    fun theCodeNeverCrossesTheWire() = test {
        val server = server()
        val port = server.start(code)
        val captured = ByteArrayOutputStream()
        val relay = Relay(port, captured)
        try {
            val guest = join(relay.port, "Ana")
            server.broadcast(state(0))
            guest.await<JamStateFrame>()
            val bytes = synchronized(captured) { captured.toByteArray() }
            assertTrue(bytes.size > 1_000, "the handshake was captured")
            assertFalse(bytes.contains(code.toByteArray(Charsets.US_ASCII)), "code in ASCII")
            assertFalse(bytes.contains(code.toByteArray(Charsets.UTF_16BE)), "code in UTF-16BE")
            assertFalse(bytes.contains(code.toByteArray(Charsets.UTF_16LE)), "code in UTF-16LE")
            assertFalse(bytes.contains(code.toInt().toBigInteger().toByteArray()), "code as a number")
        } finally {
            relay.close()
        }
    }

    @Test
    fun pingsSampleTheClockAgainstTheHostsInjectedClock() = test {
        val server = server()
        val port = server.start(code)
        val guest = join(port, "Ana")
        while (guest.clockSync.offsetMs() == null) delay(10)
        assertEquals(hostClock.now - guestClock.now, guest.clockSync.offsetMs())
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    /** A TCP relay in front of the server that records every byte in both directions. */
    private inner class Relay(target: Int, private val sink: ByteArrayOutputStream) {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val sockets = mutableListOf<Socket>()
        val port = listener.localPort

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        val inbound = listener.accept()
                        val outbound = Socket(loopback, target)
                        synchronized(sockets) { sockets += inbound; sockets += outbound }
                        pump(inbound.getInputStream(), outbound.getOutputStream())
                        pump(outbound.getInputStream(), inbound.getOutputStream())
                    }
                }
            }
        }

        private fun pump(from: InputStream, to: OutputStream) = thread(isDaemon = true) {
            runCatching {
                val buffer = ByteArray(8192)
                while (true) {
                    val n = from.read(buffer)
                    if (n < 0) break
                    synchronized(sink) { sink.write(buffer, 0, n) }
                    to.write(buffer, 0, n)
                    to.flush()
                }
            }
        }

        fun close() {
            runCatching { listener.close() }
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        }
    }
}
