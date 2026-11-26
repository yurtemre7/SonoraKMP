package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.ClockSync
import de.yurtemre.sonora.shared.jam.JamClient
import de.yurtemre.sonora.shared.jam.JamConnection
import de.yurtemre.sonora.shared.jam.JamEvent
import de.yurtemre.sonora.shared.jam.JamFrame
import de.yurtemre.sonora.shared.jam.JamServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The host's network side (a [JamServer]); a seam so the controller is tested without sockets. */
interface JamHostTransport {
    val events: Flow<JamEvent>
    val codeExhausted: StateFlow<Boolean>
    suspend fun start(code: String): Int
    fun broadcast(frame: JamFrame)
    fun sendTo(guestId: String, frame: JamFrame)
    fun kick(guestId: String)
    fun replaceCode(code: String)
    fun stop()
}

/** One guest connection (a [JamConnection]). [frames] fails with `JamException(HostGone)` on a drop. */
interface JamGuestLink {
    val frames: Flow<JamFrame>

    /** Owned by the connection's ping loop: read it inside `synchronized(clock)`. */
    val clock: ClockSync

    /** Queues [frame]; false when it was not queued (closed, or a burst filled the outbox: never a disconnect). */
    fun send(frame: JamFrame): Boolean

    /** Leaves on purpose (the host revokes the reconnect key). */
    fun close()
}

/** Opens guest connections (a [JamClient]). Failures are `JamException`s. */
interface JamGuestConnector {
    suspend fun join(host: String, port: Int, code: String, name: String): JamGuestLink

    /** Comes back after [dropped] broke, with its reconnect key (no code). */
    suspend fun reconnect(host: String, port: Int, dropped: JamGuestLink, name: String): JamGuestLink
}

class ServerHostTransport(
    private val server: JamServer,
    /** The port to try first (0 = any): a host that keeps its port stays at the address guests remember. */
    private val preferredPort: () -> Int = { 0 },
    private val onListening: (Int) -> Unit = {},
) : JamHostTransport {
    override val events get() = server.events
    override val codeExhausted get() = server.codeExhausted
    override suspend fun start(code: String): Int = server.start(code, preferredPort()).also { onListening(it) }
    override fun broadcast(frame: JamFrame) = server.broadcast(frame)
    override fun sendTo(guestId: String, frame: JamFrame) = server.sendTo(guestId, frame)
    override fun kick(guestId: String) = server.kick(guestId)
    override fun replaceCode(code: String) = server.replaceCode(code)
    override fun stop() = server.stop()
}

class ClientGuestConnector(private val client: JamClient) : JamGuestConnector {
    private class Link(val connection: JamConnection) : JamGuestLink {
        override val frames get() = connection.frames
        override val clock get() = connection.clockSync
        override fun send(frame: JamFrame) = connection.send(frame)
        override fun close() = connection.close()
    }

    override suspend fun join(host: String, port: Int, code: String, name: String): JamGuestLink =
        Link(client.join(host, port, code, name))

    override suspend fun reconnect(host: String, port: Int, dropped: JamGuestLink, name: String): JamGuestLink =
        Link(client.reconnect(host, port, (dropped as Link).connection.reconnectTicket, name))
}
