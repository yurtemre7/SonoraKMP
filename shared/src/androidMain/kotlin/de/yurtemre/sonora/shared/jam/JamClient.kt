package de.yurtemre.sonora.shared.jam

import de.yurtemre.sonora.shared.devicesync.DeviceSyncAuthException
import de.yurtemre.sonora.shared.devicesync.DeviceSyncProtocolException
import de.yurtemre.sonora.shared.devicesync.JpakeHandshake
import de.yurtemre.sonora.shared.devicesync.Role
import de.yurtemre.sonora.shared.devicesync.SecureChannel
import de.yurtemre.sonora.shared.jam.JamWire.hexToBytes
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a guest needs to come back after a drop; single-use, valid [JamServer.RECONNECT_WINDOW_MS] after the drop. */
class JamReconnectTicket internal constructor(val guestId: String, internal val key: ByteArray) {
    override fun toString() = "JamReconnectTicket(guestId=$guestId)" // never print the key
}

/**
 * The guest side of a Jam. [join] pairs with the host's code (its own J-PAKE round); [reconnect] comes back after a
 * drop with the connection's [JamConnection.reconnectTicket], without the code. Failures are [JamException]s:
 * [JamError.WrongCode], [JamError.Locked], [JamError.CodeExhausted], [JamError.Full], [JamError.VersionMismatch], [JamError.HostGone],
 * [JamError.ReconnectRefused].
 *
 * [clock] stamps pings for [ClockSync]; blocking IO runs on [dispatcher] (must allow blocking in parallel).
 */
class JamClient(
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val pairingTimeoutMs: Int = JamServer.PAIRING_TIMEOUT_MS,
    private val idleTimeoutMs: Int = JamServer.IDLE_TIMEOUT_MS,
    private val pingIntervalMs: Long = PING_INTERVAL_MS,
    private val pingReplyTimeoutMs: Long = PING_REPLY_TIMEOUT_MS,
    private val random: SecureRandom = SecureRandom(),
    internal val protocol: Int = JAM_PROTOCOL_VERSION,
) {
    suspend fun join(host: String, port: Int, code: String, name: String): JamConnection =
        connect(host, port, name) { input, output ->
            JamWire.writePreamble(output, JamWire.MODE_JOIN)
            when (input.readUnsignedByte()) {
                JamWire.STATUS_OK -> Unit
                // Busy (joins saturated) is reported like the cool-down: try again shortly.
                JamWire.STATUS_LOCKED, JamWire.STATUS_BUSY -> throw JamException(JamError.Locked)
                JamWire.STATUS_EXHAUSTED -> throw JamException(JamError.CodeExhausted)
                else -> throw JamException(JamError.HostGone)
            }
            val keyingMaterial = try {
                JpakeHandshake.run(input, output, code, Role.Receiver, "jam-guest")
            } catch (e: DeviceSyncAuthException) {
                throw JamException(JamError.WrongCode)
            } // Locked/busy/exhausted come as the status byte above; any other failure falls through to HostGone.
            SecureChannel(input, output, keyingMaterial, Role.Receiver)
        }

    suspend fun reconnect(host: String, port: Int, ticket: JamReconnectTicket, name: String): JamConnection =
        connect(host, port, name, reconnecting = true) { input, output ->
            val clientNonce = JamWire.randomBytes(random, JamWire.NONCE_SIZE)
            JamWire.writePreamble(output, JamWire.MODE_RECONNECT)
            JamWire.writeReconnectRequest(output, ticket.guestId, clientNonce)
            if (input.readUnsignedByte() != JamWire.STATUS_OK) throw JamException(JamError.ReconnectRefused)
            val serverNonce = ByteArray(JamWire.NONCE_SIZE).also(input::readFully)
            val keyingMaterial = JamWire.reconnectKeyingMaterial(ticket.key, ticket.guestId, clientNonce, serverNonce)
            SecureChannel(input, output, keyingMaterial, Role.Receiver)
        }

    private suspend fun connect(
        host: String,
        port: Int,
        name: String,
        reconnecting: Boolean = false,
        handshake: (DataInputStream, DataOutputStream) -> SecureChannel,
    ): JamConnection {
        val socket = Socket()
        val (channel, welcome) = try {
            closingOnCancel(socket) {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = pairingTimeoutMs
                socket.tcpNoDelay = true
                val input = DataInputStream(socket.getInputStream().buffered())
                val output = DataOutputStream(socket.getOutputStream().buffered())
                val channel = handshake(input, output)
                JamWire.send(channel, JamHello(protocol, name))
                when (val answer = JamWire.receive(channel)) {
                    is JamWelcome -> channel to answer
                    is JamEnd -> throw JamException(refusal(answer.reason))
                    else -> throw DeviceSyncProtocolException("Expected JamWelcome")
                }
            }
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw when (e) {
                is CancellationException, is JamException -> e
                // A refused reconnect key ends the connection at the host without an answer.
                else -> JamException(if (reconnecting) JamError.ReconnectRefused else JamError.HostGone)
            }
        }
        socket.soTimeout = idleTimeoutMs
        return JamConnection(socket, channel, welcome, clock, dispatcher, pingIntervalMs, pingReplyTimeoutMs)
    }

    private fun refusal(reason: String) = when (reason) {
        JamServer.REASON_FULL -> JamError.Full
        JamServer.REASON_VERSION_MISMATCH -> JamError.VersionMismatch
        JamServer.REASON_RECONNECT_REFUSED -> JamError.ReconnectRefused
        JamServer.REASON_CODE_REPLACED -> JamError.WrongCode // the code it proved is no longer the Jam's
        else -> JamError.HostGone
    }

    /** Runs blocking [block] on [dispatcher]; cancelling the caller closes the socket so a blocked read ends at once. */
    private suspend fun <T> closingOnCancel(socket: Socket, block: () -> T): T = withContext(dispatcher) {
        val job = coroutineContext.job
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (job.isCancelled) runCatching { socket.close() }
            }
        }
        try {
            block()
        } finally {
            closer.cancel()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        /** Clock sync is re-measured this often. */
        const val PING_INTERVAL_MS = 30_000L
        /** A window with fewer than [ClockSync.WINDOW] replies is committed after this long. */
        const val PING_REPLY_TIMEOUT_MS = 2_000L
    }
}

/**
 * One guest connection. [frames] carries the host's frames ([JamStateFrame], [JamEnd], …; the internal
 * [JamWelcome] and [Pong] are consumed here). It completes after the host's [JamEnd] or [close], and fails with
 * [JamException] ([JamError.HostGone]) when the connection breaks; then [reconnectTicket] lets the playback layer
 * call [JamClient.reconnect].
 *
 * Clock sync: a burst of [ClockSync.WINDOW] pings at once, then every [pingIntervalMs] a [ClockSync.reset] and a
 * new burst; a window still short of replies after [pingReplyTimeoutMs] is [ClockSync.commit]ted. Read the offset
 * from [clockSync] (synchronize on it for compound reads).
 */
class JamConnection internal constructor(
    private val socket: Socket,
    private val channel: SecureChannel,
    welcome: JamWelcome,
    private val clock: () -> Long,
    dispatcher: CoroutineDispatcher,
    private val pingIntervalMs: Long,
    private val pingReplyTimeoutMs: Long,
) {
    internal val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private val incoming = Channel<JamFrame>(Channel.UNLIMITED)
    private val outbox = Channel<JamFrame>(OUTBOX_CAPACITY)
    private val closing = AtomicBoolean(false)
    @Volatile private var windowStartId = 0L
    private var nextPingId = 0L

    val guestId: String = welcome.guestId
    val clockSync = ClockSync()
    val frames: Flow<JamFrame> = incoming.receiveAsFlow()

    @Volatile var reconnectTicket: JamReconnectTicket = welcome.ticket()
        private set

    private val reader = scope.launch(start = CoroutineStart.LAZY) { readLoop() }
    private val writer = scope.launch(start = CoroutineStart.LAZY) {
        JamWire.drain(outbox, channel, socket, JamServer.SEND_TIMEOUT_MS)
    }
    private val pinger = scope.launch(start = CoroutineStart.LAZY) { pingLoop() }

    init {
        reader.start()
        writer.start()
        pinger.start()
    }

    /** Queues [frame] for the host; never blocks. */
    fun send(frame: JamFrame) {
        val queued = outbox.trySend(frame)
        if (queued.isFailure && !queued.isClosed) abort() // cannot keep up: the connection is broken
    }

    /**
     * Leaves the Jam on purpose: sends [JamEnd] (the host revokes the reconnect key) and closes once the host has
     * read it. Idempotent.
     */
    fun close() {
        if (!closing.compareAndSet(false, true)) return
        pinger.cancel()
        outbox.trySend(JamEnd(JamServer.REASON_LEFT))
        outbox.close()
        job.complete()
    }

    /** Closes at once without telling the host (a drop): the reconnect ticket stays valid for the window. */
    internal fun abort() {
        closing.set(true)
        pinger.cancel()
        outbox.close()
        runCatching { socket.close() }
        job.complete()
    }

    /** Suspends until every coroutine of a closed connection has finished. */
    suspend fun awaitClosed() = job.join()

    private fun readLoop() {
        var failure: Throwable? = null
        try {
            while (true) {
                when (val frame = JamWire.receive(channel)) {
                    is Pong -> synchronized(clockSync) {
                        if (frame.id >= windowStartId) clockSync.addSample(frame.sentMs, frame.hostMs, clock())
                    }
                    is JamWelcome -> reconnectTicket = frame.ticket()
                    is JamEnd -> {
                        incoming.trySend(frame)
                        break
                    }
                    else -> incoming.trySend(frame)
                }
            }
        } catch (e: Exception) {
            if (!closing.get()) failure = JamException(JamError.HostGone)
        } finally {
            closing.set(true)
            incoming.close(failure)
            pinger.cancel()
            outbox.close()
            writer.cancel()
            runCatching { socket.close() }
            job.complete()
        }
    }

    private suspend fun pingLoop() {
        var first = true
        while (!closing.get()) {
            synchronized(clockSync) {
                if (!first) clockSync.reset()
                windowStartId = nextPingId
            }
            first = false
            repeat(ClockSync.WINDOW) { send(Ping(nextPingId++, clock())) }
            delay(pingReplyTimeoutMs)
            synchronized(clockSync) { clockSync.commit() }
            delay((pingIntervalMs - pingReplyTimeoutMs).coerceAtLeast(0))
        }
    }

    private fun JamWelcome.ticket() = JamReconnectTicket(guestId, reconnectKey.hexToBytes())

    private companion object {
        const val OUTBOX_CAPACITY = 64
    }
}
