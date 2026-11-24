package de.yurtemre.sonora.shared.jam

import de.yurtemre.sonora.shared.devicesync.DeviceSyncAuthException
import de.yurtemre.sonora.shared.devicesync.DeviceSyncProtocolException
import de.yurtemre.sonora.shared.devicesync.JpakeHandshake
import de.yurtemre.sonora.shared.devicesync.PairingCode
import de.yurtemre.sonora.shared.devicesync.PairingLockout
import de.yurtemre.sonora.shared.devicesync.Role
import de.yurtemre.sonora.shared.devicesync.SecureChannel
import de.yurtemre.sonora.shared.devicesync.toHex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Jam host's TCP endpoint: up to [MAX_GUESTS] guests, each on its own [SecureChannel] (wire format: [JamWire]).
 *
 * - **Joining:** every connection runs its own J-PAKE round with the Jam's code (nothing is stored as a trusted
 *   device), then sends [JamHello]. Another [JAM_PROTOCOL_VERSION] is answered with [JamEnd] [REASON_VERSION_MISMATCH],
 *   a full Jam with [REASON_FULL]; otherwise the guest gets a [JamWelcome] (its id and a fresh 128-bit reconnect key)
 *   and [events] gets [JamEvent.GuestJoined].
 * - **Lockout:** the device sync rule ([PairingLockout]): 3 wrong codes, then joins are refused for the cool-down
 *   ([lockedUntilMs], by [clock]). Join J-PAKE rounds run one at a time, so parallel connections cannot get more
 *   guesses in before the lock. After [MAX_WRONG_CODES] wrong codes in total the code is dead ([codeExhausted])
 *   until the host calls [replaceCode]. Reconnects never need the code and are not affected by either.
 * - **Handshake limits:** at most [MAX_HANDSHAKES] connections between accept and registration, of which joins may
 *   hold [MAX_JOIN_HANDSHAKES] (the rest stay free for reconnects); each has a total deadline of
 *   [pairingTimeoutMs] from accept, however slowly it trickles bytes.
 * - **Reconnect:** a guest that drops (not one that left, was kicked, or a stopped Jam) may come back with its key
 *   within [RECONNECT_WINDOW_MS] of the drop ([clock]); its slot stays reserved meanwhile. Keys are single-use and
 *   bound to the guest id; each reconnect gets a fresh key. See [JamWire] for the handshake.
 * - **Sending:** [broadcast] / [sendTo] never block: each guest has a bounded queue ([sendQueueCapacity]) drained by
 *   its own writer; a guest whose queue is full, or whose write takes longer than [sendTimeoutMs], is disconnected
 *   (as a drop: it may reconnect) so it cannot hold up the others.
 * - **Guest frames:** [Ping] is answered with [Pong] stamped by [clock]; [JamAddRequest] / [JamControl] become
 *   [JamEvent.AddRequest] / [JamEvent.Control] ([clock] time; at most [MAX_REQUESTS_PER_SECOND] per guest, more are
 *   ignored); [JamEnd] from a guest means it left.
 *
 * Blocking IO runs on [dispatcher], which must allow blocking in parallel (default [Dispatchers.IO]). [stop] sends
 * [JamEnd] to every guest, closes all sockets and lets every coroutine finish ([awaitTermination]).
 */
class JamServer(
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pairingTimeoutMs: Int = PAIRING_TIMEOUT_MS,
    private val idleTimeoutMs: Int = IDLE_TIMEOUT_MS,
    private val sendTimeoutMs: Long = SEND_TIMEOUT_MS,
    private val sendQueueCapacity: Int = SEND_QUEUE_CAPACITY,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(sendQueueCapacity > ClockSync.WINDOW) { "The queue must hold a full ping burst's replies" }
    }

    internal val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private val lockout = PairingLockout(clock)
    private val eventChannel = Channel<JamEvent>(Channel.UNLIMITED)

    /** Guest joins/leaves and requests, in order. Single consumer; completes after [stop]. */
    val events: Flow<JamEvent> = eventChannel.receiveAsFlow()

    /** Clock time until which joins are refused after too many wrong codes; 0 before the first lock. */
    val lockedUntilMs: StateFlow<Long> = lockout.lockedUntilMs
    private val _codeExhausted = MutableStateFlow(false)
    private val _joinHandshakes = MutableStateFlow(0)

    /** True after [MAX_WRONG_CODES] wrong codes: joins are refused until [replaceCode]. */
    val codeExhausted: StateFlow<Boolean> = _codeExhausted.asStateFlow()

    /** Join connections between their preamble and the end of their J-PAKE round (waiting ones included). */
    internal val joinHandshakes: StateFlow<Int> = _joinHandshakes.asStateFlow()

    /** Test seam: runs inside [broadcast] between taking the guest snapshot and queueing. */
    @Volatile internal var beforeDeliver: (() -> Unit)? = null

    /** One join J-PAKE round at a time, so every guess is counted before the next one is checked. */
    private val joinMutex = Mutex()

    private val lock = Any()
    private val guests = HashMap<String, Guest>()
    private val tickets = HashMap<String, Ticket>()
    private val handshaking = HashSet<Socket>()
    private var serverSocket: ServerSocket? = null
    private var stopped = false
    private var code = ""
    /** Wrong codes against [code] in total (the timed lock resets its own count, this one only on [replaceCode]). */
    private var codeFailures = 0

    /** A reconnect key; [disconnectedAtMs] is null while its guest is connected. */
    private class Ticket(val key: ByteArray) {
        var disconnectedAtMs: Long? = null
    }

    private inner class Guest(val id: String, val socket: Socket, val channel: SecureChannel) {
        val outbox = Channel<JamFrame>(sendQueueCapacity)
        private var windowStartMs = Long.MIN_VALUE
        private var windowCount = 0

        /** At most [MAX_REQUESTS_PER_SECOND] AddRequest/Control events per second ([clock]); reader thread only. */
        fun allowRequest(): Boolean {
            val now = clock()
            if (windowStartMs == Long.MIN_VALUE || now - windowStartMs >= 1_000) {
                windowStartMs = now
                windowCount = 0
            }
            return ++windowCount <= MAX_REQUESTS_PER_SECOND
        }

        fun offer(frame: JamFrame): Boolean = outbox.trySend(frame).isSuccess

        /** Queues [frame] last; the writer half-closes the socket once the queue is drained. */
        fun finish(frame: JamFrame) {
            outbox.trySend(frame)
            outbox.close()
        }

        fun drop() {
            outbox.close()
            runCatching { socket.close() }
        }
    }

    private sealed interface Admission {
        class In(val guest: Guest) : Admission
        class Out(val reason: String) : Admission
    }

    /** Binds a free port on all interfaces, starts accepting guests who know [code] and returns the port. */
    suspend fun start(code: String): Int {
        requireCode(code)
        val socket = withContext(dispatcher) { ServerSocket(0) }
        synchronized(lock) {
            if (serverSocket != null || stopped) {
                runCatching { socket.close() }
                error("JamServer is single-use")
            }
            serverSocket = socket
            this.code = code
        }
        scope.launch { acceptLoop(socket) }
        return socket.localPort
    }

    /**
     * Switches joins to [newCode] (the host shows it instead): clears the wrong-code counts, the cool-down and
     * [codeExhausted]. Guests in the Jam and valid reconnect keys are unaffected; a kicked guest who knew the old
     * code cannot use it any more.
     */
    fun replaceCode(newCode: String) {
        requireCode(newCode)
        synchronized(lock) {
            code = newCode
            codeFailures = 0
            _codeExhausted.value = false
            lockout.clear()
        }
    }

    private fun requireCode(code: String) =
        require(code.length == PairingCode.LENGTH && code.all { it in '0'..'9' }) { "Bad Jam code" }

    /** Queues [frame] for every guest; never blocks. */
    fun broadcast(frame: JamFrame) {
        val snapshot = synchronized(lock) { guests.values.toList() }
        beforeDeliver?.invoke()
        snapshot.forEach { deliver(it, frame) }
    }

    /** Queues [frame] for one guest (e.g. the current state for a guest that just joined); never blocks. */
    fun sendTo(guestId: String, frame: JamFrame) {
        synchronized(lock) { guests[guestId] }?.let { deliver(it, frame) }
    }

    /** Removes a guest: it gets [JamEnd] [REASON_KICKED], its reconnect key is revoked. */
    fun kick(guestId: String) {
        val guest = synchronized(lock) {
            tickets.remove(guestId)
            guests.remove(guestId)?.also { eventChannel.trySend(JamEvent.GuestLeft(guestId)) }
        }
        guest?.finish(JamEnd(REASON_KICKED))
    }

    /** Ends the Jam: [JamEnd] [REASON_HOST_ENDED] to every guest, then everything closes. Idempotent. */
    fun stop() {
        val (ending, pending, listener) = synchronized(lock) {
            if (stopped) return
            stopped = true
            tickets.clear()
            Triple(
                guests.values.toList().also { guests.clear() },
                handshaking.toList().also { handshaking.clear() },
                serverSocket,
            )
        }
        runCatching { listener?.close() }
        pending.forEach { runCatching { it.close() } }
        ending.forEach { it.finish(JamEnd(REASON_HOST_ENDED)) }
        eventChannel.close()
        // Every remaining coroutine ends by itself: writers drain and half-close (bounded by sendTimeoutMs per write
        // and once more for the guest to close), readers end when the guest closes; the accept loop ends with the
        // listener.
        job.complete()
    }

    /** Suspends until every coroutine of a [stop]ped server has finished. */
    suspend fun awaitTermination() = job.join()

    private fun deliver(guest: Guest, frame: JamFrame) {
        val queued = guest.outbox.trySend(frame)
        // Full = cannot keep up: drop it (it may reconnect) rather than wait. Closed = kicked or stopped meanwhile:
        // leave it alone so its writer still delivers the final JamEnd.
        if (queued.isFailure && !queued.isClosed) guest.drop()
    }

    private fun CoroutineScope.acceptLoop(listener: ServerSocket) {
        while (isActive) {
            val socket = try {
                listener.accept()
            } catch (e: IOException) {
                return // closed by stop()
            }
            val admitted = synchronized(lock) { !stopped && handshaking.size < MAX_HANDSHAKES && handshaking.add(socket) }
            if (!admitted) {
                runCatching { socket.close() }
                continue
            }
            launch { serve(socket) }
        }
    }

    private suspend fun serve(socket: Socket) {
        var guest: Guest? = null
        try {
            // A total deadline from accept: the read timeout alone restarts with every byte a slow peer trickles.
            guest = coroutineScope {
                val deadline = launch {
                    delay(pairingTimeoutMs.toLong())
                    runCatching { socket.close() } // ends blocking IO
                }
                try {
                    withTimeoutOrNull(pairingTimeoutMs.toLong()) { admit(socket) } // ends a wait for the join slot
                } finally {
                    deadline.cancel()
                }
            } ?: return
            run(guest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Disconnect, timeout, malformed data, a wrong reconnect key: the connection just ends.
        } finally {
            synchronized(lock) { handshaking.remove(socket) }
            if (guest == null) runCatching { socket.close() }
        }
    }

    /** Pairs, checks the hello and registers the guest; null when refused (the refusal was sent). */
    private suspend fun admit(socket: Socket): Guest? {
        socket.soTimeout = pairingTimeoutMs
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())
        val channel: SecureChannel
        var reconnect: Pair<String, Ticket>? = null
        if (JamWire.readPreamble(input) == JamWire.MODE_JOIN) {
            // Joins get MAX_JOIN_HANDSHAKES of the MAX_HANDSHAKES slots, so slow joins cannot keep reconnects out.
            val slot = synchronized(lock) {
                (_joinHandshakes.value < MAX_JOIN_HANDSHAKES).also { if (it) _joinHandshakes.value++ }
            }
            if (!slot) {
                JamWire.writeStatus(output, JamWire.STATUS_BUSY)
                return null
            }
            channel = try {
                joinMutex.withLock { pair(input, output) } ?: return null
            } finally {
                synchronized(lock) { _joinHandshakes.value-- }
            }
        } else {
            val (guestId, clientNonce) = JamWire.readReconnectRequest(input)
            val ticket = synchronized(lock) { tickets[guestId]?.takeIf(::redeemable) }
            if (ticket == null) {
                output.writeByte(JamWire.STATUS_REFUSED)
                output.flush()
                return null
            }
            val serverNonce = JamWire.randomBytes(random, JamWire.NONCE_SIZE)
            output.writeByte(JamWire.STATUS_OK)
            output.write(serverNonce)
            output.flush()
            val keyingMaterial = JamWire.reconnectKeyingMaterial(ticket.key, guestId, clientNonce, serverNonce)
            channel = SecureChannel(input, output, keyingMaterial, Role.Sender)
            reconnect = guestId to ticket
        }
        // On a reconnect this first record is the key proof: a wrong key fails its tag check here.
        val hello = JamWire.receive(channel) as? JamHello ?: throw DeviceSyncProtocolException("Expected JamHello")
        if (hello.protocol != JAM_PROTOCOL_VERSION) {
            JamWire.send(channel, JamEnd(REASON_VERSION_MISMATCH))
            return null
        }
        return when (val admission = register(socket, channel, hello.name.take(MAX_NAME_LENGTH), reconnect)) {
            is Admission.In -> admission.guest
            is Admission.Out -> {
                JamWire.send(channel, JamEnd(admission.reason))
                null
            }
        }
    }

    /** The join's J-PAKE round, under [joinMutex]; null when refused or the code was wrong. */
    private fun pair(input: DataInputStream, output: DataOutputStream): SecureChannel? {
        val code = synchronized(lock) { code.takeUnless { _codeExhausted.value } }
        val status = when {
            code == null -> JamWire.STATUS_EXHAUSTED
            lockout.isLocked() -> JamWire.STATUS_LOCKED
            else -> JamWire.STATUS_OK
        }
        JamWire.writeStatus(output, status)
        if (code == null || status != JamWire.STATUS_OK) return null
        val keyingMaterial = try {
            JpakeHandshake.run(input, output, code, Role.Sender, "jam-host")
        } catch (e: DeviceSyncAuthException) {
            recordWrongCode(code)
            return null
        }
        return SecureChannel(input, output, keyingMaterial, Role.Sender)
    }

    /** Counts a wrong guess against [code], unless the host has replaced it meanwhile. */
    private fun recordWrongCode(code: String) = synchronized(lock) {
        if (code != this.code) return
        lockout.recordFailure()
        if (++codeFailures >= MAX_WRONG_CODES) _codeExhausted.value = true
    }

    private fun register(socket: Socket, channel: SecureChannel, name: String, reconnect: Pair<String, Ticket>?): Admission =
        synchronized(lock) {
            handshaking.remove(socket)
            if (stopped) return Admission.Out(REASON_HOST_ENDED)
            tickets.entries.removeAll { (id, ticket) -> id !in guests && !redeemable(ticket) }
            val id = if (reconnect == null) {
                val reserved = tickets.count { (id, _) -> id !in guests }
                if (guests.size + reserved >= MAX_GUESTS) return Admission.Out(REASON_FULL)
                newGuestId()
            } else {
                val (id, ticket) = reconnect
                if (tickets[id] !== ticket) return Admission.Out(REASON_RECONNECT_REFUSED) // used or revoked meanwhile
                guests.remove(id)?.let { stale -> // the host had not noticed the drop yet
                    eventChannel.trySend(JamEvent.GuestLeft(id))
                    stale.drop()
                }
                id
            }
            val key = JamWire.randomBytes(random, JamWire.KEY_SIZE)
            tickets[id] = Ticket(key)
            val guest = Guest(id, socket, channel)
            guest.offer(JamWelcome(id, key.toHex())) // first in its queue, before any broadcast can reach it
            guests[id] = guest
            eventChannel.trySend(JamEvent.GuestJoined(id, name))
            Admission.In(guest)
        }

    private fun redeemable(ticket: Ticket): Boolean {
        val since = ticket.disconnectedAtMs ?: return true
        return clock() - since < RECONNECT_WINDOW_MS
    }

    private fun newGuestId(): String {
        var id: String
        do id = JamWire.randomBytes(random, GUEST_ID_BYTES).toHex() while (id in tickets || id in guests)
        return id
    }

    /** Serves a registered guest until it leaves, drops, or (kicked / stopped) has read its [JamEnd] and closed. */
    private suspend fun run(guest: Guest) = coroutineScope {
        guest.socket.soTimeout = idleTimeoutMs
        val writer = launch { JamWire.drain(guest.outbox, guest.channel, guest.socket, sendTimeoutMs) }
        val left = try {
            readLoop(guest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        detach(guest, revokeTicket = left) // no-op when kicked or stopped
        guest.drop()
        writer.cancel()
    }

    /**
     * Returns true when the guest left with [JamEnd]; throws when the connection breaks. Keeps reading after a kick
     * or [stop] until the guest closes, so the final [JamEnd] is not lost to a reset.
     */
    private fun readLoop(guest: Guest): Boolean {
        while (true) {
            when (val frame = JamWire.receive(guest.channel)) {
                is Ping -> {
                    val queued = guest.outbox.trySend(Pong(frame.id, frame.sentMs, clock()))
                    if (queued.isFailure && !queued.isClosed) return false // queue full: cannot keep up
                }
                is JamAddRequest -> if (guest.allowRequest()) emitFrom(guest, JamEvent.AddRequest(guest.id, frame.song))
                is JamControl -> if (guest.allowRequest()) emitFrom(guest, JamEvent.Control(guest.id, frame.action, clock()))
                is JamEnd -> return true
                else -> throw DeviceSyncProtocolException("Unexpected Jam frame from a guest")
            }
        }
    }

    private fun emitFrom(guest: Guest, event: JamEvent) = synchronized(lock) {
        if (guests[guest.id] === guest) eventChannel.trySend(event)
    }

    /** Unregisters [guest] if it still is; false when it was already removed (kick, stop, replaced). */
    private fun detach(guest: Guest, revokeTicket: Boolean): Boolean = synchronized(lock) {
        if (guests[guest.id] !== guest) return false
        guests.remove(guest.id)
        if (revokeTicket) tickets.remove(guest.id) else tickets[guest.id]?.disconnectedAtMs = clock()
        eventChannel.trySend(JamEvent.GuestLeft(guest.id))
        true
    }

    companion object {
        /** A dropped guest may reconnect with its key for this long after the drop. */
        const val RECONNECT_WINDOW_MS = 30_000L
        /** Read timeout from accept until the guest is registered. */
        const val PAIRING_TIMEOUT_MS = 10_000
        /** Read timeout while a guest is in the Jam; guests ping every [JamClient.PING_INTERVAL_MS]. */
        const val IDLE_TIMEOUT_MS = 75_000
        /** Longest a single write to one guest may take before that guest is dropped. */
        const val SEND_TIMEOUT_MS = 5_000L
        /** Frames queued per guest before a guest that cannot keep up is dropped. */
        const val SEND_QUEUE_CAPACITY = 64
        /** Connections in their handshake at once (from accept, bounded by [PAIRING_TIMEOUT_MS]); more are closed. */
        const val MAX_HANDSHAKES = 10
        /** Of [MAX_HANDSHAKES], joins may hold this many; the rest stay free for reconnects. */
        const val MAX_JOIN_HANDSHAKES = 6
        /** Wrong codes in total before the code is dead until [replaceCode] (the timed lock comes every 3). */
        const val MAX_WRONG_CODES = 10
        /** AddRequest/Control events per guest and second; more are ignored. */
        const val MAX_REQUESTS_PER_SECOND = 20
        const val MAX_NAME_LENGTH = 64
        private const val GUEST_ID_BYTES = 8

        /** [JamEnd] reasons. */
        const val REASON_HOST_ENDED = "host_ended"
        const val REASON_KICKED = "kicked"
        const val REASON_FULL = "full"
        const val REASON_VERSION_MISMATCH = "version_mismatch"
        const val REASON_RECONNECT_REFUSED = "reconnect_refused"
        /** Sent by a guest that leaves on purpose ([JamConnection.close]). */
        const val REASON_LEFT = "left"
    }
}
