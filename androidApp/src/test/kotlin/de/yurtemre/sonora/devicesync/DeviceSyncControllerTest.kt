package de.yurtemre.sonora.devicesync

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.yurtemre.sonora.shared.devicesync.AudioMeta
import de.yurtemre.sonora.shared.devicesync.DeviceSyncAuthException
import de.yurtemre.sonora.shared.devicesync.DeviceSyncProtocolException
import de.yurtemre.sonora.shared.devicesync.FetchResult
import de.yurtemre.sonora.shared.devicesync.FileKind
import de.yurtemre.sonora.shared.devicesync.Hello
import de.yurtemre.sonora.shared.devicesync.Manifest
import de.yurtemre.sonora.shared.devicesync.ManifestFile
import de.yurtemre.sonora.shared.devicesync.ReceiverIndex
import de.yurtemre.sonora.shared.devicesync.ReceiverSong
import de.yurtemre.sonora.shared.devicesync.ServerState
import de.yurtemre.sonora.shared.devicesync.Status
import de.yurtemre.sonora.shared.devicesync.SyncBundle
import de.yurtemre.sonora.shared.devicesync.SyncStats
import de.yurtemre.sonora.shared.devicesync.TransferProgress
import de.yurtemre.sonora.shared.repo.api.DeviceSyncApplyResult
import de.yurtemre.sonora.shared.repo.api.DeviceSyncDataException
import de.yurtemre.sonora.shared.repo.api.DeviceSyncDataRepository
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeviceSyncControllerTest {
    private val dest: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
    private val log = mutableListOf<String>()

    // ---- fakes -------------------------------------------------------------------------------------------------

    private class FakeSession : SenderSession {
        override val code = MutableStateFlow("123456")
        override val codeExpiresAtMs = MutableStateFlow(300_000L)
        override val state = MutableStateFlow<ServerState>(ServerState.Waiting)
        override val address = "192.168.1.5:4000"
        var stopped = false
        override fun stop() { stopped = true }
    }

    private class FakeSink(val log: MutableList<String>) : CommittingSyncFileSink {
        val begun = mutableListOf<Int>()
        val aborted = mutableListOf<Int>()
        override val committed = LinkedHashMap<Int, String>()
        override fun begin(file: ManifestFile): OutputStream { begun += file.id; return ByteArrayOutputStream() }
        override fun commit(file: ManifestFile, sha256Hex: String) { committed[file.id] = file.relPath; log += "commit:${file.id}" }
        override fun abort(file: ManifestFile) { aborted += file.id }
    }

    private inner class FakeClient(
        private val manifest: Manifest,
        private val connectError: Exception? = null,
        private val senderFps: Map<Int, String> = emptyMap(),
        /** When set, fetch begins the first file and then waits here (cancellation aborts it). */
        private val fetchGate: CompletableDeferred<Unit>? = null,
        private val fetchError: Exception? = null,
        private val failIds: Set<Int> = emptySet(),
    ) : SyncClient {
        var connectedCode: String? = null
        var hello: Hello? = null
        val fingerprintRequests = mutableListOf<List<Int>>()
        var fetched: List<Int>? = null
        var closed = false

        override suspend fun connect(host: String, port: Int, code: String, hello: Hello): Manifest {
            connectedCode = code
            this.hello = hello
            connectError?.let { throw it }
            return manifest
        }

        override suspend fun fingerprints(ids: List<Int>): Map<Int, String> {
            fingerprintRequests += ids
            return senderFps.filterKeys { it in ids }
        }

        override suspend fun fetch(ids: List<Int>, sink: de.yurtemre.sonora.shared.devicesync.SyncFileSink, onProgress: (TransferProgress) -> Unit): FetchResult {
            fetched = ids
            log += "fetch"
            val files = manifest.files.associateBy { it.id }
            if (fetchGate != null) {
                val f = files.getValue(ids.first())
                sink.begin(f)
                onProgress(TransferProgress(f.id, 1, f.size, 1, f.size, 0, ids.size, 10))
                try { fetchGate.await() } catch (e: Throwable) { sink.abort(f); throw e }
            }
            val committed = mutableListOf<Int>()
            val failed = linkedMapOf<Int, String>()
            for (id in ids) {
                val f = files.getValue(id)
                sink.begin(f).close()
                if (id in failIds) { sink.abort(f); failed[id] = "checksum_mismatch"; continue }
                sink.commit(f, "00")
                committed += id
                onProgress(TransferProgress(id, f.size, f.size, 0, 0, committed.size, ids.size, 1000))
            }
            fetchError?.let { throw it }
            return FetchResult(committed, failed)
        }

        override suspend fun close() { closed = true }
    }

    private inner class FakeStorage(
        var default: Uri? = dest,
        var free: Long? = 10_000_000_000L,
        var index: ReceiverIndex = ReceiverIndex(emptyMap(), emptyList()),
        var receiverFps: Map<String, String> = emptyMap(),
    ) : ReceiverStorage {
        val sinks = mutableListOf<FakeSink>()
        val scanned = mutableListOf<Uri>()
        override suspend fun defaultDestination() = default
        override suspend fun pickerInitialUri(): Uri = dest
        override fun rootPath(destination: Uri) = "/dest"
        /** When set, the first scan waits here. */
        var scanGate: CompletableDeferred<Unit>? = null
        override suspend fun scan(destination: Uri): ReceiverScan {
            scanned += destination
            scanGate?.let { scanGate = null; it.await() }
            return ReceiverScan(index, emptyMap())
        }
        override suspend fun fingerprints(scan: ReceiverScan, relPaths: List<String>) = receiverFps.filterKeys { it in relPaths }
        override suspend fun freeBytes(destination: Uri) = free
        override fun openSink(destination: Uri) = FakeSink(log).also { sinks += it }
    }

    private inner class FakeLibrary(
        val idsByPath: Map<String, Long> = emptyMap(),
        val pathsById: Map<Long, String> = emptyMap(),
    ) : ReceiverLibrary {
        val indexed = mutableListOf<String>()
        override suspend fun indexNewFiles(paths: List<String>) { indexed += paths }
        override suspend fun sync(): SyncOutcome { log += "sync"; return SyncOutcome.Success(1, 1, emptyMap()) }
        override suspend fun songIdByPath(path: String) = idsByPath[path]
        override suspend fun songPath(songId: Long) = pathsById[songId]
    }

    private inner class FakeData(private val applyError: Exception? = null) : DeviceSyncDataRepository {
        var applied: Pair<SyncBundle, String>? = null
        val relinks = mutableListOf<Map<Long, Long>>()
        override suspend fun deviceId() = "rx-device"
        override suspend fun exportBundle() = error("receiver never exports")
        override suspend fun applyBundle(bundle: SyncBundle, senderDeviceId: String): DeviceSyncApplyResult {
            log += "apply"
            applyError?.let { throw it }
            applied = bundle to senderDeviceId
            return DeviceSyncApplyResult(2, 3, 4, 5, 6, false)
        }
        override suspend fun relinkReplaced(oldToNew: Map<Long, Long>) { log += "relink"; relinks += oldToNew }
    }

    private inner class FakeDeleter(private val needsConfirm: Boolean) : ReplacedFileDeleter {
        val requested = mutableListOf<String>()
        override suspend fun delete(paths: List<String>, destination: Uri, confirm: suspend (IntentSender) -> Boolean): Set<String> {
            log += "delete"
            requested += paths
            if (!needsConfirm) return paths.toSet()
            return if (confirm(intentSender())) paths.toSet() else emptySet()
        }
    }

    private fun intentSender(): IntentSender {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        return PendingIntent.getBroadcast(ctx, 0, Intent("x"), PendingIntent.FLAG_IMMUTABLE).intentSender
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private val bundle = SyncBundle("{}", SyncStats())

    private fun audio(id: Int, path: String, size: Long, title: String = "T$id") =
        ManifestFile(id, path, size, 0, FileKind.Audio, AudioMeta(title, "Artist", "Album", 100_000))

    private fun manifest(vararg files: ManifestFile) = Manifest(1, "tx-device", "Sender phone", bundle, files.toList())

    private var serviceStarts = 0
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun cancelScopes() = scopes.forEach { it.cancel() }
    private val createdClients = mutableListOf<SyncClient>()

    private fun TestScope.controller(
        clientFor: () -> SyncClient,
        storage: FakeStorage = FakeStorage(),
        library: FakeLibrary = FakeLibrary(),
        data: FakeData = FakeData(),
        deleter: ReplacedFileDeleter = FakeDeleter(needsConfirm = false),
        discovery: Flow<List<DiscoveredSender>> = flow { },
        session: FakeSession = FakeSession(),
    ) = DeviceSyncController(
        // backgroundScope work is skipped by advanceUntilIdle, so the controller gets its own scope on the scheduler.
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it },
        senders = { session },
        clients = { clientFor().also { createdClients += it } },
        discovery = { discovery },
        storage = storage,
        library = library,
        data = data,
        deleter = deleter,
        deviceName = { "Receiver phone" },
        clock = { testScheduler.currentTime },
        service = { serviceStarts++ },
    )

    private val target = SyncTarget("192.168.1.5", 4000, "Sender phone")

    // ---- tests -------------------------------------------------------------------------------------------------

    @Test fun sendHappyPath() = runTest {
        val session = FakeSession()
        val c = controller(clientFor = { error("unused") }, session = session)
        c.startSending()
        advanceUntilIdle()
        assertEquals(DeviceSyncState.Sending("123456", 300_000L, "192.168.1.5:4000", ServerState.Waiting), c.state.value)
        assertEquals(1, serviceStarts)

        session.state.value = ServerState.Paired("Receiver phone")
        session.code.value = "654321"
        advanceUntilIdle()
        val s = assertIs<DeviceSyncState.Sending>(c.state.value)
        assertEquals("654321", s.code)
        assertEquals(ServerState.Paired("Receiver phone"), s.server)

        c.cancel()
        advanceUntilIdle()
        assertTrue(session.stopped)
        assertEquals(DeviceSyncState.Idle, c.state.value)
    }

    @Test fun receiveHappyPathThroughToFinished() = runTest {
        val m = manifest(audio(0, "A/new.mp3", 100), audio(1, "B/same.mp3", 30), audio(2, "B/fail.mp3", 40))
        val storage = FakeStorage(
            index = ReceiverIndex(mapOf("C/other.mp3" to 30L), emptyList()),
            receiverFps = mapOf("C/other.mp3" to "fp-same"),
        )
        val library = FakeLibrary()
        val data = FakeData()
        val senders = listOf(DiscoveredSender("svc", "Sender phone", "192.168.1.5", 4000))
        val c = controller({ FakeClient(m, senderFps = mapOf(1 to "fp-same"), failIds = setOf(2)) }, storage, library, data,
            discovery = flow { emit(senders) })
        c.startReceiving()
        advanceUntilIdle()
        assertEquals(DeviceSyncState.Discovering(senders), c.state.value)

        c.connect(SyncTarget.of(senders.single()), "111222")
        advanceUntilIdle()
        val planning = assertIs<DeviceSyncState.Planning>(c.state.value)
        assertEquals("Sender phone", planning.senderName)
        assertEquals(dest, planning.destination)
        val plan = assertNotNull(planning.plan)
        // The ambiguous same-size file was resolved by fingerprints (sender batch + receiver candidates).
        assertEquals(Status.Present, plan.items.single { it.file.id == 1 }.status)
        assertEquals(listOf(listOf(1)), (createdClients.single() as FakeClient).fingerprintRequests)
        val first = createdClients.single() as FakeClient
        assertEquals("111222", first.connectedCode)
        assertEquals(Hello(1, "rx-device", "Receiver phone"), first.hello)

        assertTrue(c.startTransfer(plan.defaultSelection()))
        advanceUntilIdle()
        assertEquals(listOf(0, 2), first.fetched)
        assertTrue(first.closed)
        val summary = assertIs<DeviceSyncState.Finished>(c.state.value).summary
        assertEquals(1, summary.filesCopied)
        assertEquals(0, summary.filesReplaced)
        assertEquals(listOf(FailedFile("B/fail.mp3", "checksum_mismatch")), summary.failed)
        assertEquals(100L, summary.bytes)
        assertEquals(2, summary.playlists)
        assertEquals(3, summary.favorites)
        assertEquals(4, summary.settings)
        assertEquals(5, summary.statsSongsAdded)
        assertEquals(bundle to "tx-device", data.applied)
        assertEquals(listOf("/dest/A/new.mp3"), library.indexed)
        assertEquals(listOf("fetch", "commit:0", "sync", "apply"), log)
        assertEquals(1, serviceStarts)
    }

    @Test fun wrongCodeFailsWithCodeMismatchAndRetryReturnsToDiscovery() = runTest {
        val m = manifest(audio(0, "A/x.mp3", 10))
        val c = controller({ FakeClient(m, connectError = DeviceSyncAuthException("code_mismatch")) })
        c.startReceiving()
        c.connect(target, "000000")
        advanceUntilIdle()
        val failed = assertIs<DeviceSyncState.Failed>(c.state.value)
        assertEquals(DeviceSyncFailure.CodeMismatch, failed.reason)
        assertTrue(failed.canRetry)

        c.retry()
        advanceUntilIdle()
        // A wrong code is never resent: the user enters a new one.
        assertIs<DeviceSyncState.Discovering>(c.state.value)
        assertEquals(1, createdClients.size)
    }

    @Test fun versionMismatchIsItsOwnReason() = runTest {
        val m = manifest(audio(0, "A/x.mp3", 10))
        val c = controller({ FakeClient(m, connectError = DeviceSyncProtocolException("version_mismatch")) })
        c.startReceiving()
        c.connect(target, "000000")
        advanceUntilIdle()
        assertEquals(DeviceSyncFailure.VersionMismatch, assertIs<DeviceSyncState.Failed>(c.state.value).reason)
    }

    @Test fun cancelMidTransferAbortsTheFileAndAppliesNothing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val m = manifest(audio(0, "A/x.mp3", 10))
        val storage = FakeStorage()
        val library = FakeLibrary()
        val data = FakeData()
        val c = controller({ FakeClient(m, fetchGate = gate) }, storage, library, data)
        c.startReceiving()
        c.connect(target, "123123")
        advanceUntilIdle()
        val plan = assertNotNull(assertIs<DeviceSyncState.Planning>(c.state.value).plan)
        assertTrue(c.startTransfer(plan.defaultSelection()))
        advanceUntilIdle()
        assertIs<DeviceSyncState.Transferring>(c.state.value)

        c.cancel()
        advanceUntilIdle()
        assertEquals(DeviceSyncState.Idle, c.state.value)
        val sink = storage.sinks.single()
        assertEquals(listOf(0), sink.aborted)
        assertTrue(sink.committed.isEmpty())
        assertNull(data.applied)
        assertTrue(data.relinks.isEmpty())
        assertFalse("sync" in log)
        assertTrue((createdClients.single() as FakeClient).closed)
    }

    private fun replaceSetup(): Triple<Manifest, FakeStorage, FakeLibrary> {
        val m = manifest(audio(0, "A/song.flac", 500, title = "Song"))
        val old = ReceiverSong(5, "A/song.mp3", "Song", "Artist", "Album", 100_000, 120, "mp3")
        val storage = FakeStorage(index = ReceiverIndex(mapOf("A/song.mp3" to 120L), listOf(old)))
        val library = FakeLibrary(idsByPath = mapOf("/dest/A/song.flac" to 9L), pathsById = mapOf(5L to "/dest/A/song.mp3"))
        return Triple(m, storage, library)
    }

    @Test fun replaceRelinksBeforeDeletingTheOldFile() = runTest {
        val (m, storage, library) = replaceSetup()
        val data = FakeData()
        val deleter = FakeDeleter(needsConfirm = false)
        val c = controller({ FakeClient(m) }, storage, library, data, deleter)
        c.startReceiving()
        c.connect(target, "123123")
        advanceUntilIdle()
        val plan = assertNotNull(assertIs<DeviceSyncState.Planning>(c.state.value).plan)
        assertEquals(Status.Replace, plan.items.single().status)
        assertTrue(c.startTransfer(plan.defaultSelection()))
        advanceUntilIdle()

        assertEquals(listOf(mapOf(5L to 9L)), data.relinks)
        assertEquals(listOf("/dest/A/song.mp3"), deleter.requested)
        // Rescan, relink, delete (then a rescan drops the deleted song), then the data bundle.
        assertEquals(listOf("fetch", "commit:0", "sync", "relink", "delete", "sync", "apply"), log)
        val summary = assertIs<DeviceSyncState.Finished>(c.state.value).summary
        assertEquals(1, summary.filesReplaced)
        assertEquals(0, summary.filesKeptBoth)
        assertEquals(0, summary.filesCopied)
    }

    @Test fun declinedDeleteKeepsBothFiles() = runTest {
        val (m, storage, library) = replaceSetup()
        val data = FakeData()
        val c = controller({ FakeClient(m) }, storage, library, data, FakeDeleter(needsConfirm = true))
        c.startReceiving()
        c.connect(target, "123123")
        advanceUntilIdle()
        val plan = assertNotNull(assertIs<DeviceSyncState.Planning>(c.state.value).plan)
        assertTrue(c.startTransfer(plan.defaultSelection()))
        advanceUntilIdle()

        assertEquals(DeviceSyncState.Applying(ApplyStep.Replacing), c.state.value)
        assertNotNull(c.pendingDeleteRequest.value)
        c.onDeleteResult(granted = false)
        advanceUntilIdle()

        assertNull(c.pendingDeleteRequest.value)
        assertEquals(listOf(mapOf(5L to 9L)), data.relinks)
        val summary = assertIs<DeviceSyncState.Finished>(c.state.value).summary
        assertEquals(0, summary.filesReplaced)
        assertEquals(1, summary.filesKeptBoth)
        assertEquals(listOf("fetch", "commit:0", "sync", "relink", "delete", "apply"), log)
    }

    @Test fun replaceWithoutIndexedNewSongDeletesNothing() = runTest {
        val (m, storage, _) = replaceSetup()
        val data = FakeData()
        val deleter = FakeDeleter(needsConfirm = false)
        val c = controller({ FakeClient(m) }, storage, FakeLibrary(pathsById = mapOf(5L to "/dest/A/song.mp3")), data, deleter)
        c.startReceiving()
        c.connect(target, "123123")
        advanceUntilIdle()
        val plan = assertNotNull(assertIs<DeviceSyncState.Planning>(c.state.value).plan)
        assertTrue(c.startTransfer(plan.defaultSelection()))
        advanceUntilIdle()

        assertTrue(deleter.requested.isEmpty())
        assertTrue(data.relinks.isEmpty())
        assertEquals(1, assertIs<DeviceSyncState.Finished>(c.state.value).summary.filesKeptBoth)
    }

    @Test fun insufficientSpaceBlocksStartTransfer() = runTest {
        val m = manifest(audio(0, "A/x.mp3", 50))
        val storage = FakeStorage(free = 100L)
        var client: FakeClient? = null
        val c = controller({ FakeClient(m).also { client = it } }, storage)
        c.startReceiving()
        c.connect(target, "123123")
        advanceUntilIdle()
        val planning = assertIs<DeviceSyncState.Planning>(c.state.value)
        assertFalse(planning.fits(setOf(0)))

        assertFalse(c.startTransfer(setOf(0)))
        advanceUntilIdle()
        assertIs<DeviceSyncState.Planning>(c.state.value)
        assertNull(client!!.fetched)
        assertEquals(0, serviceStarts)
    }

    @Test fun missingDestinationIsReportedAndSetDestinationReplans() = runTest {
        val m = manifest(audio(0, "A/x.mp3", 50))
        val storage = FakeStorage(default = null)
        val c = controller({ FakeClient(m) }, storage)
        c.startReceiving()
        c.connect(target, "123123")
        advanceUntilIdle()
        val p = assertIs<DeviceSyncState.Planning>(c.state.value)
        assertTrue(p.needsDestination)
        assertNull(p.plan)
        assertFalse(c.startTransfer(setOf(0)))

        val picked = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        c.setDestination(picked)
        advanceUntilIdle()
        val p2 = assertIs<DeviceSyncState.Planning>(c.state.value)
        assertEquals(picked, p2.destination)
        assertNotNull(p2.plan)
        assertEquals(listOf(picked), storage.scanned)
    }

    @Test fun destinationChangedWhilePlanningIsPickedUp() = runTest {
        val m = manifest(audio(0, "A/x.mp3", 50))
        val gate = CompletableDeferred<Unit>()
        val storage = FakeStorage().apply { scanGate = gate }
        val c = controller({ FakeClient(m) }, storage)
        c.startReceiving()
        c.connect(target, "123123")
        advanceUntilIdle()
        assertTrue(assertIs<DeviceSyncState.Planning>(c.state.value).computing)
        assertFalse(c.startTransfer(setOf(0)))

        val picked = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        c.setDestination(picked)
        gate.complete(Unit)
        advanceUntilIdle()
        val p = assertIs<DeviceSyncState.Planning>(c.state.value)
        assertEquals(picked, p.destination)
        assertFalse(p.computing)
        assertEquals(listOf(dest, picked), storage.scanned)
        assertTrue(c.startTransfer(setOf(0)))
    }

    @Test fun discoveryFailureKeepsDiscoveringWithFlag() = runTest {
        val c = controller({ error("unused") }, discovery = flow { throw IOException("discovery_failed:0") })
        c.startReceiving()
        advanceUntilIdle()
        assertEquals(DeviceSyncState.Discovering(emptyList(), discoveryFailed = true), c.state.value)
    }

    @Test fun networkFailureMidTransferRetriesWithTheSameCode() = runTest {
        val m = manifest(audio(0, "A/x.mp3", 10), audio(1, "A/y.mp3", 10))
        var attempt = 0
        val c = controller({
            attempt++
            if (attempt == 1) FakeClient(m, fetchError = IOException("reset")) else FakeClient(m)
        })
        c.startReceiving()
        c.connect(target, "424242")
        advanceUntilIdle()
        val plan = assertNotNull(assertIs<DeviceSyncState.Planning>(c.state.value).plan)
        assertTrue(c.startTransfer(plan.defaultSelection()))
        advanceUntilIdle()
        val failed = assertIs<DeviceSyncState.Failed>(c.state.value)
        assertEquals(DeviceSyncFailure.Network, failed.reason)
        assertEquals(2, failed.filesKept)
        assertTrue(failed.canRetry)

        c.retry()
        advanceUntilIdle()
        assertIs<DeviceSyncState.Planning>(c.state.value)
        assertEquals(2, createdClients.size)
        assertEquals("424242", (createdClients[1] as FakeClient).connectedCode)
    }

    @Test fun dataApplyFailureKeepsTheFiles() = runTest {
        val m = manifest(audio(0, "A/x.mp3", 10))
        val data = FakeData(applyError = DeviceSyncDataException.WriteFailed(IOException("disk"), rolledBack = true))
        val c = controller({ FakeClient(m) }, data = data)
        c.startReceiving()
        c.connect(target, "123123")
        advanceUntilIdle()
        val plan = assertNotNull(assertIs<DeviceSyncState.Planning>(c.state.value).plan)
        assertTrue(c.startTransfer(plan.defaultSelection()))
        advanceUntilIdle()
        val failed = assertIs<DeviceSyncState.Failed>(c.state.value)
        assertEquals(DeviceSyncFailure.DataApply, failed.reason)
        assertEquals(1, failed.filesKept)
    }
}
