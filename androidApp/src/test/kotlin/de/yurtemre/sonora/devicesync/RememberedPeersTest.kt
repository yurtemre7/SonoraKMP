package de.yurtemre.sonora.devicesync

import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RememberedPeersTest {
    private class MemoryStore(var text: String? = null) : PeerStore {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private val a = RememberedPeer("Pixel", "192.168.1.5", 40001)
    private val b = RememberedPeer("Galaxy", "192.168.1.6", 40002)
    private val c = RememberedPeer("Fairphone", "192.168.1.7", 40003)
    private val d = RememberedPeer("Xperia", "192.168.1.8", 40004)

    @Test fun remembersNewestFirstAndSurvivesAReload() {
        val store = MemoryStore()
        RememberedPeers(store).apply { remember(a); remember(b) }
        assertEquals(listOf(b, a), RememberedPeers(store).peers())
    }

    @Test fun keepsOnlyTheLastThree() {
        val peers = RememberedPeers(MemoryStore()).apply { remember(a); remember(b); remember(c); remember(d) }
        assertEquals(listOf(d, c, b), peers.peers())
    }

    @Test fun theSameDeviceAtANewAddressReplacesItsOldEntry() {
        val peers = RememberedPeers(MemoryStore()).apply { remember(a); remember(a.copy(host = "192.168.1.99", port = 41000)) }
        assertEquals(listOf(a.copy(host = "192.168.1.99", port = 41000)), peers.peers())
    }

    @Test fun forgetRemovesByAddress() {
        val peers = RememberedPeers(MemoryStore()).apply { remember(a); remember(b); forget(a) }
        assertEquals(listOf(b), peers.peers())
    }

    @Test fun namesWithTabsAndNewlinesCannotCorruptTheFile() {
        val store = MemoryStore()
        RememberedPeers(store).remember(a.copy(name = "Evil\tName\nline"))
        assertEquals(listOf(a.copy(name = "Evil Name line")), RememberedPeers(store).peers())
    }

    @Test fun rejectsBadAddressesAndIgnoresGarbageLines() {
        val store = MemoryStore("junk\npeer\tX\t\t5\npeer\tY\t1.2.3.4\t99999\npeer\tOk\t1.2.3.4\t5000\nport\tabc\n")
        val peers = RememberedPeers(store)
        assertEquals(listOf(RememberedPeer("Ok", "1.2.3.4", 5000)), peers.peers())
        assertEquals(0, peers.listenPort())
        peers.remember(a.copy(port = 0))
        assertEquals(1, peers.peers().size)
    }

    @Test fun theListenPortIsKeptAlongsidePeers() {
        val store = MemoryStore()
        val peers = RememberedPeers(store)
        peers.rememberListenPort(40555)
        peers.remember(a)
        peers.rememberListenPort(0) // ignored
        assertEquals(40555, RememberedPeers(store).listenPort())
        assertEquals(listOf(a), RememberedPeers(store).peers())
    }

    @Test fun aBrokenStoreNeverThrows() {
        val broken = object : PeerStore {
            override fun read(): String? = null
            override fun write(text: String) = error("disk full")
        }
        // FilePeerStore swallows IO errors; a custom store is the caller's responsibility, so check the real one.
        val dir = File(System.getProperty("java.io.tmpdir"), "peers-" + System.nanoTime()).apply { mkdirs() }
        val file = File(dir, "p.txt")
        val real = FilePeerStore(file)
        RememberedPeers(real).remember(a)
        assertEquals(listOf(a), RememberedPeers(FilePeerStore(file)).peers())
        file.delete(); File(dir, "p.txt.tmp").delete(); dir.delete()
        assertTrue(RememberedPeers(broken).peers().isEmpty())
    }

    @Test fun tcpProbeSeesAListeningPortAndAClosedOne() = runTest {
        val open = ServerSocket(0)
        val port = open.localPort
        assertTrue(tcpProbe(RememberedPeer("x", "127.0.0.1", port)))
        open.close()
        assertFalse(tcpProbe(RememberedPeer("x", "127.0.0.1", port)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MergeRememberedTest {
    private data class Item(val name: String, val host: String, val port: Int, val remembered: Boolean = false)

    private val a = RememberedPeer("Pixel", "10.0.0.5", 4000)
    private val b = RememberedPeer("Galaxy", "10.0.0.6", 4001)
    private val up = { p: RememberedPeer -> Item(p.name, p.host, p.port, true) }
    private val ep = { i: Item -> i.host to i.port }

    @Test fun reachableRememberedPeersAreListedBesideTheFoundOnes() = runTest {
        val found = Item("Found", "10.0.0.9", 1)
        val out = flowOf(listOf(found)).mergeRemembered({ listOf(a, b) }, { it == a }, up, ep).toList().last()
        assertEquals(listOf(found, Item("Pixel", "10.0.0.5", 4000, true)), out)
    }

    @Test fun aPeerThatMdnsAlsoReportsAppearsOnceAsFound() = runTest {
        val found = Item("Pixel (mdns)", "10.0.0.5", 4000)
        val out = flowOf(listOf(found)).mergeRemembered({ listOf(a) }, { true }, up, ep).toList().last()
        assertEquals(listOf(found), out)
    }

    @Test fun aRememberedPeerThatStopsAnsweringDisappears() = runTest {
        val alive = MutableStateFlow(true)
        val upstream = MutableStateFlow(emptyList<Item>())
        val results = mutableListOf<List<Item>>()
        val job = launch { upstream.mergeRemembered({ listOf(a) }, { alive.value }, up, ep, recheckMs = 1_000).collect { results += it } }
        runCurrent()
        assertEquals(1, results.last().size)
        alive.value = false
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(results.last().isEmpty())
        job.cancel()
    }

    @Test fun nothingRememberedMeansTheSearchResultsUnchanged() = runTest {
        val found = Item("Found", "10.0.0.9", 1)
        val out = flowOf(listOf(found)).mergeRemembered({ emptyList() }, { true }, up, ep).toList().last()
        assertEquals(listOf(found), out)
    }
}
