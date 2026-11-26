package de.yurtemre.sonora.devicesync

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A device this phone connected to before: where it was last reached and what it was called. */
data class RememberedPeer(val name: String, val host: String, val port: Int)

/** Where [RememberedPeers] keeps its text; a failing store must never break a connection. */
interface PeerStore {
    fun read(): String?
    fun write(text: String)
}

/** One text file, replaced atomically. */
class FilePeerStore(private val file: File) : PeerStore {
    override fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    override fun write(text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
        }
    }
}

/**
 * The last few devices this phone connected to (device sync senders, or Jam hosts) and the port this phone itself
 * last listened on. Both make the next search faster: a remembered peer is tried directly while the mDNS search
 * runs ([mergeRemembered]), and a host that reuses its [listenPort] keeps the address a peer remembers valid.
 * A peer is keyed by its name: reaching it again at a new address replaces the old entry. Newest first, at most [max].
 */
class RememberedPeers(private val store: PeerStore, private val max: Int = MAX_PEERS) {
    private val lock = Any()

    fun peers(): List<RememberedPeer> = synchronized(lock) { load().first }

    /** The port to try first when hosting; 0 = none remembered. */
    fun listenPort(): Int = synchronized(lock) { load().second }

    fun remember(peer: RememberedPeer) = synchronized(lock) {
        val (peers, port) = load()
        val clean = peer.copy(name = clean(peer.name))
        if (clean.host.isBlank() || clean.port !in 1..65535) return@synchronized
        save(listOf(clean) + peers.filter { it.name != clean.name && !(it.host == clean.host && it.port == clean.port) }, port)
    }

    fun forget(peer: RememberedPeer) = synchronized(lock) {
        val (peers, port) = load()
        save(peers.filter { !(it.host == peer.host && it.port == peer.port) }, port)
    }

    fun rememberListenPort(port: Int) = synchronized(lock) {
        if (port !in 1..65535) return@synchronized
        val (peers, current) = load()
        if (current != port) save(peers, port)
    }

    private fun load(): Pair<List<RememberedPeer>, Int> {
        var port = 0
        val peers = ArrayList<RememberedPeer>()
        store.read()?.lineSequence()?.forEach { line ->
            val f = line.split('\t')
            when {
                f.size == 2 && f[0] == "port" -> port = f[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: 0
                f.size == 4 && f[0] == "peer" -> {
                    val p = f[3].toIntOrNull()?.takeIf { it in 1..65535 }
                    if (p != null && f[2].isNotBlank()) peers += RememberedPeer(f[1], f[2], p)
                }
            }
        }
        return peers.take(max) to port
    }

    private fun save(peers: List<RememberedPeer>, port: Int) {
        val text = buildString {
            if (port != 0) append("port\t").append(port).append('\n')
            peers.take(max).forEach { append("peer\t").append(it.name).append('\t').append(it.host).append('\t').append(it.port).append('\n') }
        }
        store.write(text)
    }

    private fun clean(name: String) = name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    companion object {
        const val MAX_PEERS = 3
    }
}

/** True when something accepts a TCP connection at [peer] within [timeoutMs]; never throws. */
suspend fun tcpProbe(peer: RememberedPeer, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean = withContext(Dispatchers.IO) {
    withTimeoutOrNull(timeoutMs + 200L) {
        runCatching { Socket().use { it.connect(InetSocketAddress(peer.host, peer.port), timeoutMs) } }.isSuccess
    } ?: false
}

private const val PROBE_TIMEOUT_MS = 400

/**
 * Adds the remembered peers that currently answer to this search's list, so a device met before shows up in
 * milliseconds instead of waiting for mDNS. Remembered peers are probed ([probe]) right away and again every
 * [recheckMs] while the flow is collected, so one that went away disappears. A peer that mDNS also reports (same
 * [endpoint]) appears once, as mDNS reported it. The search's own failure is passed on unchanged.
 */
fun <T : Any> Flow<List<T>>.mergeRemembered(
    peers: () -> List<RememberedPeer>,
    probe: suspend (RememberedPeer) -> Boolean,
    toItem: (RememberedPeer) -> T,
    endpoint: (T) -> Pair<String, Int>,
    recheckMs: Long = RECHECK_MS,
): Flow<List<T>> {
    val reachable = flow {
        while (true) {
            emit(coroutineScope { peers().map { p -> async { p.takeIf { probe(it) } } }.awaitAll().filterNotNull() })
            delay(recheckMs)
        }
    }.distinctUntilChanged().onStart { emit(emptyList()) }
    return combine(this, reachable) { found, remembered ->
        val known = found.mapTo(HashSet(), endpoint)
        found + remembered.filter { (it.host to it.port) !in known }.map(toItem)
    }
}

private const val RECHECK_MS = 5_000L
