package de.yurtemre.sonora.devicesync

import android.content.IntentSender
import android.net.Uri
import de.yurtemre.sonora.shared.devicesync.DeviceSyncAuthException
import de.yurtemre.sonora.shared.devicesync.DeviceSyncProtocolException
import de.yurtemre.sonora.shared.devicesync.DeviceSyncServer
import de.yurtemre.sonora.shared.devicesync.FetchResult
import de.yurtemre.sonora.shared.devicesync.Hello
import de.yurtemre.sonora.shared.devicesync.Manifest
import de.yurtemre.sonora.shared.devicesync.PlanItem
import de.yurtemre.sonora.shared.devicesync.ReceiverSong
import de.yurtemre.sonora.shared.devicesync.ServerState
import de.yurtemre.sonora.shared.devicesync.Status
import de.yurtemre.sonora.shared.devicesync.SyncPaths
import de.yurtemre.sonora.shared.devicesync.TransferPlan
import de.yurtemre.sonora.shared.devicesync.TransferPlanner
import de.yurtemre.sonora.shared.devicesync.TransferProgress
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.DeviceSyncDataException
import de.yurtemre.sonora.shared.repo.api.DeviceSyncDataRepository
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide device sync state machine (DSYNC-01..13). The UI observes [state] and calls the commands; the
 * [DeviceSyncService] keeps the process alive while sending or transferring.
 *
 * Sender: [startSending] builds the manifest, starts the server and advertises it until [cancel].
 * Receiver: [startReceiving] → pick a sender (or [connectManual]) and [connect] with the code (R3) → [state] is
 * [DeviceSyncState.Planning]; [setDestination] re-plans → [startTransfer] → files, library rescan, Replace
 * (relink, then delete the old file: R2), data bundle → [DeviceSyncState.Finished]. Any failure →
 * [DeviceSyncState.Failed]; [retry] reconnects with the same code while it is likely still valid and re-plans
 * (verified files are then Present).
 *
 * Work runs in [scope]; the ports do their blocking IO on Dispatchers.IO. Codes and keys are never logged.
 */
class DeviceSyncController(
    private val scope: CoroutineScope,
    private val senders: SenderSessionFactory,
    private val clients: SyncClientFactory,
    private val discovery: SenderDiscoverySource,
    private val storage: ReceiverStorage,
    private val library: ReceiverLibrary,
    private val data: DeviceSyncDataRepository,
    private val deleter: ReplacedFileDeleter,
    private val deviceName: DeviceNameSource,
    private val clock: Clock,
    private val service: DeviceSyncServiceStarter,
) {
    private enum class Role { Sender, Receiver }

    private val lock = Any()
    private val _state = MutableStateFlow<DeviceSyncState>(DeviceSyncState.Idle)
    val state: StateFlow<DeviceSyncState> = _state.asStateFlow()

    private val _pendingDeleteRequest = MutableStateFlow<IntentSender?>(null)

    /** MediaStore delete confirmation (API 30+) the UI launches; answer with [onDeleteResult]. */
    val pendingDeleteRequest: StateFlow<IntentSender?> = _pendingDeleteRequest.asStateFlow()

    // All fields below are guarded by [lock].
    /** Bumped whenever the session is replaced or cancelled; work of an older generation may not touch [state]. */
    private var generation = 0
    private var role: Role? = null
    private var discoveryJob: Job? = null
    private var workJob: Job? = null
    private var client: SyncClient? = null
    private var manifest: Manifest? = null
    private var destination: Uri? = null
    private var target: SyncTarget? = null
    private var code: String? = null
    private var codeEnteredAtMs = 0L
    /** False once the sender has seen DONE (it then shows a new code) or the code was rejected. */
    private var codeUsable = false
    private var deleteAnswer: CompletableDeferred<Boolean>? = null
    /** A [plan] loop is running; it re-reads [destination] before it publishes (cleared atomically with that). */
    private var planInFlight = false

    // ---- commands ----------------------------------------------------------------------------------------------

    fun startSending() {
        val gen = synchronized(lock) {
            resetLocked()
            role = Role.Sender
            generation
        }
        launchWork(gen) {
            val session = try {
                senders.start(deviceName.deviceName())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw RunFailure(DeviceSyncFailure.SenderStart, 0, e)
            }
            try {
                // The service reads the state when it starts: it must already be Sending, never Idle.
                set(gen, DeviceSyncState.Sending(session.code.value, session.codeExpiresAtMs.value, session.address, session.state.value))
                service.ensureRunning()
                combine(session.code, session.codeExpiresAtMs, session.state) { c, exp, s ->
                    DeviceSyncState.Sending(c, exp, session.address, s)
                }.takeWhile { it.server != ServerState.Done && it.server != ServerState.Stopped }
                    .collect { set(gen, it) }
                // The receiver fetched what it wanted and sent DONE: the sending session is over.
                set(gen, DeviceSyncState.Finished(null))
            } finally {
                session.stop()
            }
        }
    }

    fun startReceiving() = synchronized(lock) {
        resetLocked()
        beginDiscoveryLocked()
    }

    /** Pairs with [target] using [code] (R3: one sender at a time, picked by the user). */
    fun connect(target: SyncTarget, code: String) = synchronized(lock) {
        this.code = code
        codeEnteredAtMs = clock.nowMs()
        codeUsable = true
        connectLocked(target, code)
    }

    /** [text] is a manually entered `IP:port`; false (and nothing happens) when it does not parse. */
    fun connectManual(text: String, code: String): Boolean {
        val (host, port) = DeviceSyncDiscovery.parseManual(text) ?: return false
        connect(SyncTarget(host, port), code)
        return true
    }

    /** Sets the receiver's destination (after OpenDocumentTree + persisted write access); re-plans when connected. */
    fun setDestination(uri: Uri) = synchronized(lock) {
        destination = uri
        val planning = _state.value as? DeviceSyncState.Planning ?: return@synchronized
        // A running plan picks the new destination up before it publishes (see plan()); never cancel it, because
        // cancelling a client call ends the session.
        if (planInFlight) return@synchronized
        planInFlight = true
        _state.value = planning.copy(destination = uri, plan = null, freeBytes = null, computing = true)
        val gen = generation
        launchWorkLocked(gen) { plan(gen) }
    }

    suspend fun pickerInitialUri(): Uri = storage.pickerInitialUri()

    /**
     * Starts fetching the selected New/Replace items, then applies the data bundle. Refuses (false) unless a plan
     * for a writable destination is ready and the selection fits the free space with the 200 MB margin. An empty
     * selection fetches nothing but still applies the bundle (data-only device sync).
     */
    fun startTransfer(selectedIds: Set<Int>): Boolean {
        synchronized(lock) {
            val planning = _state.value as? DeviceSyncState.Planning ?: return false
            val plan = planning.plan ?: return false
            val dest = planning.destination ?: return false
            if (planning.computing || planInFlight) return false
            val items = plan.items.filter { it.file.id in selectedIds && it.status != Status.Present }
            // An empty selection is valid: nothing to fetch (e.g. everything already Present), but the data
            // bundle (playlists, favorites, settings, stats) is still applied.
            if (!planning.fits(selectedIds)) return false
            val ids = items.map { it.file.id }
            _state.value = DeviceSyncState.Transferring(
                TransferProgress(-1, 0, 0, 0, plan.bytesNeeded(selectedIds), 0, ids.size, 0),
            )
            val gen = generation
            launchWorkLocked(gen) { receive(gen, plan, items, dest) }
        }
        service.ensureRunning()
        return true
    }

    /** Stops whatever runs (sender, discovery, transfer, apply) and returns to [DeviceSyncState.Idle]. */
    fun cancel() = synchronized(lock) { resetLocked() }

    /**
     * After [DeviceSyncState.Failed]: the sender restarts; the receiver reconnects with the same code when it is
     * likely still valid (not rejected, not used up by a finished session, younger than the code lifetime),
     * otherwise it goes back to discovery for a new code.
     */
    fun retry() = synchronized(lock) {
        val failed = _state.value as? DeviceSyncState.Failed ?: return@synchronized
        if (!failed.canRetry) return@synchronized
        when (role) {
            Role.Sender -> startSending() // re-takes the (reentrant) lock
            Role.Receiver -> {
                val t = target
                val c = code
                val fresh = clock.nowMs() - codeEnteredAtMs < DeviceSyncServer.CODE_LIFETIME_MS
                if (t != null && c != null && codeUsable && fresh) {
                    connectLocked(t, c)
                } else {
                    generation++
                    cancelJobsLocked()
                    closeClientLocked()
                    beginDiscoveryLocked()
                }
            }
            null -> resetLocked()
        }
    }

    /** The answer to [pendingDeleteRequest] (RESULT_OK → true). */
    fun onDeleteResult(granted: Boolean) {
        synchronized(lock) { deleteAnswer }?.complete(granted)
    }

    // ---- receiver work -----------------------------------------------------------------------------------------

    private fun connectLocked(target: SyncTarget, code: String) {
        generation++
        cancelJobsLocked()
        closeClientLocked()
        role = Role.Receiver
        this.target = target
        manifest = null
        _state.value = DeviceSyncState.Connecting(target)
        val gen = generation
        val c = clients.create()
        client = c
        launchWorkLocked(gen) {
            val hello = Hello(DeviceSyncServer.PROTOCOL_VERSION, data.deviceId(), deviceName.deviceName())
            val m = try {
                c.connect(target.host, target.port, code, hello)
            } catch (e: DeviceSyncAuthException) {
                synchronized(lock) { if (gen == generation) codeUsable = false }
                throw e
            }
            val needsDefault = synchronized(lock) {
                if (gen != generation) return@launchWorkLocked
                manifest = m
                destination == null
            }
            if (needsDefault) {
                val d = storageStep { storage.defaultDestination() }
                synchronized(lock) { if (gen == generation && destination == null) destination = d }
            }
            synchronized(lock) {
                if (gen != generation) return@launchWorkLocked
                planInFlight = true
            }
            plan(gen)
        }
    }

    /** Plans against the current destination, again if it changed meanwhile, and publishes [DeviceSyncState.Planning]. */
    private suspend fun plan(gen: Int) = try {
        planLoop(gen)
    } finally {
        synchronized(lock) { if (gen == generation) planInFlight = false }
    }

    private suspend fun planLoop(gen: Int) {
        while (true) {
            val (m, c, dest) = synchronized(lock) {
                if (gen != generation) return
                Triple(manifest ?: return, client ?: return, destination)
            }
            if (dest == null) {
                synchronized(lock) {
                    if (gen != generation) return
                    if (destination == null) {
                        planInFlight = false
                        _state.value = DeviceSyncState.Planning(m.deviceName, null, null, null)
                        return
                    }
                }
                continue
            }
            set(gen, DeviceSyncState.Planning(m.deviceName, dest, null, null, computing = true))
            val scan = storageStep { storage.scan(dest) }
            var plan = TransferPlanner.plan(m.files, scan.index)
            val ambiguous = plan.items.filter { it.needsFingerprint }.map { it.file.id }
            if (ambiguous.isNotEmpty()) {
                val senderFps = fetchSenderFingerprints(ambiguous) { c.fingerprints(it) }
                val candidates = fingerprintCandidates(plan.items, scan.index.existing)
                val receiverFps = storageStep { storage.fingerprints(scan, candidates) }
                plan = TransferPlan(resolveAmbiguous(plan.items, senderFps, receiverFps), plan.rejected)
            }
            val free = storageStep { storage.freeBytes(dest) }
            synchronized(lock) {
                if (gen != generation) return
                if (destination == dest) {
                    planInFlight = false
                    _state.value = DeviceSyncState.Planning(m.deviceName, dest, plan, free)
                    return
                }
            }
        }
    }

    private suspend fun receive(gen: Int, plan: TransferPlan, items: List<PlanItem>, dest: Uri) {
        val (m, c) = synchronized(lock) {
            if (gen != generation) return
            Pair(checkNotNull(manifest) { "no manifest" }, checkNotNull(client) { "no client" })
        }
        val sink = if (items.isEmpty()) null else storageStep { storage.openSink(dest) }
        try {
            val result = if (sink == null) {
                FetchResult(emptyList(), emptyMap())
            } else {
                c.fetch(items.map { it.file.id }, sink) { set(gen, DeviceSyncState.Transferring(it)) }
            }
            // DONE ends the session; the sender then shows a new code.
            c.close()
            synchronized(lock) {
                if (client === c) client = null
                codeUsable = false
            }
            val committed = sink?.committed.orEmpty()
            val summary = afterTransfer(gen, m, plan, items, dest, committed)
            val failed = result.failed.mapNotNull { (id, reason) ->
                items.firstOrNull { it.file.id == id }?.let { FailedFile(it.file.relPath, reason) }
            }
            set(gen, DeviceSyncState.Finished(summary.copy(failed = failed)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RunFailure(failureOf(e), sink?.committed?.size ?: 0, e)
        }
    }

    /** Library rescan, Replace (relink first, then delete: R2), then the data bundle (DSYNC-12/13). */
    private suspend fun afterTransfer(
        gen: Int,
        m: Manifest,
        plan: TransferPlan,
        items: List<PlanItem>,
        dest: Uri,
        committed: Map<Int, String>,
    ): DeviceSyncSummary {
        val root = storage.rootPath(dest)?.trimEnd('/')
        fun absolute(rel: String) = "$root/$rel"
        var idRemap = emptyMap<Long, Long>()
        if (root != null && committed.isNotEmpty()) {
            set(gen, DeviceSyncState.Applying(ApplyStep.LibrarySync))
            idRemap = storageStep {
                library.indexNewFiles(committed.values.map(::absolute))
                (library.sync() as? SyncOutcome.Success)?.idRemap.orEmpty()
            }
        }

        val replaceItems = items.filter { it.status == Status.Replace && it.replaces != null && it.file.id in committed }
        val oldToNew = LinkedHashMap<Long, Long>()
        val oldPathById = HashMap<Long, String>()
        if (root != null) {
            for (item in replaceItems) {
                val planned = item.replaces!!
                val newPath = absolute(committed.getValue(item.file.id))
                // Never touch the old file unless the new one is committed *and* indexed.
                val newId = storageStep { library.songIdByPath(newPath) } ?: continue
                val oldSongId = idRemap[planned.songId] ?: planned.songId
                // Re-read the old song now: ids can shift between planning and here (another library sync).
                val old = storageStep { library.song(oldSongId) } ?: continue
                if (!stillPlannedSong(old, planned, root)) continue
                if (old.path == newPath || oldSongId == newId) continue
                oldToNew[oldSongId] = newId
                oldPathById[oldSongId] = old.path
            }
        }
        var deleted = emptySet<String>()
        if (oldToNew.isNotEmpty()) {
            set(gen, DeviceSyncState.Applying(ApplyStep.Replacing))
            dataStep { data.relinkReplaced(oldToNew) }
            deleted = storageStep { deleter.delete(oldPathById.values.toList(), dest, ::confirmDelete) }
            if (deleted.isNotEmpty()) storageStep { library.sync() } // drop the deleted songs from the library
        }
        val replaced = oldPathById.values.count { it in deleted }

        set(gen, DeviceSyncState.Applying(ApplyStep.Data))
        val applied = data.applyBundle(m.bundle, m.deviceId)
        val bytes = plan.items.filter { it.file.id in committed }.sumOf { it.file.size }
        return DeviceSyncSummary(
            filesCopied = committed.size - replaceItems.size,
            filesReplaced = replaced,
            filesKeptBoth = replaceItems.size - replaced,
            failed = emptyList(),
            bytes = bytes,
            playlists = applied.playlists,
            favorites = applied.favorites,
            settings = applied.settings,
            statsSongsAdded = applied.statsSongsAdded,
            completeListensAdded = applied.completeListensAdded,
            localeChanged = applied.localeChanged,
            rescanSkipped = root == null && committed.isNotEmpty(),
        )
    }

    private suspend fun confirmDelete(request: IntentSender): Boolean {
        val answer = CompletableDeferred<Boolean>()
        synchronized(lock) {
            deleteAnswer = answer
            _pendingDeleteRequest.value = request
        }
        return try {
            // No answer from the UI (screen closed, process UI recreated): count it as declined, keep both files.
            withTimeoutOrNull(DELETE_CONFIRM_TIMEOUT_MS) { answer.await() } ?: false
        } finally {
            synchronized(lock) {
                if (deleteAnswer === answer) {
                    deleteAnswer = null
                    _pendingDeleteRequest.value = null
                }
            }
        }
    }

    // ---- plumbing ----------------------------------------------------------------------------------------------

    private fun beginDiscoveryLocked() {
        generation++
        role = Role.Receiver
        _state.value = DeviceSyncState.Discovering()
        val gen = generation
        discoveryJob = scope.launch {
            discovery.discover()
                .catch { e ->
                    // discover() fails with an IOException when mDNS cannot run: the UI offers manual IP:port.
                    if (e is CancellationException) throw e
                    update(gen) { s -> (s as? DeviceSyncState.Discovering)?.copy(discoveryFailed = true) }
                }
                .collect { list ->
                    update(gen) { s -> (s as? DeviceSyncState.Discovering)?.copy(senders = list, discoveryFailed = false) }
                }
        }
    }

    /** Cancels everything, closes the connection and forgets the session. */
    private fun resetLocked() {
        generation++
        cancelJobsLocked()
        closeClientLocked()
        role = null
        manifest = null
        destination = null
        target = null
        code = null
        codeUsable = false
        _state.value = DeviceSyncState.Idle
    }

    private fun cancelJobsLocked() {
        discoveryJob?.cancel()
        discoveryJob = null
        workJob?.cancel()
        workJob = null
        planInFlight = false
        deleteAnswer?.complete(false)
        deleteAnswer = null
        _pendingDeleteRequest.value = null
    }

    private fun closeClientLocked() {
        val c = client ?: return
        client = null
        scope.launch { c.close() }
    }

    private fun launchWork(gen: Int, block: suspend () -> Unit) = synchronized(lock) { launchWorkLocked(gen, block) }

    private fun launchWorkLocked(gen: Int, block: suspend () -> Unit) {
        if (gen != generation) return
        workJob = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(gen, e)
            }
        }
    }

    private fun onFailure(gen: Int, e: Exception) = synchronized(lock) {
        if (gen != generation) return@synchronized
        val reason = (e as? RunFailure)?.reason ?: failureOf(e)
        val kept = (e as? RunFailure)?.filesKept ?: 0
        // A failed client call has already ended the session; closing is a no-op then.
        closeClientLocked()
        if (reason == DeviceSyncFailure.CodeMismatch) codeUsable = false
        _state.value = DeviceSyncState.Failed(reason, kept, canRetry = reason != DeviceSyncFailure.VersionMismatch)
    }

    private fun set(gen: Int, s: DeviceSyncState) = synchronized(lock) {
        if (gen == generation) _state.value = s
    }

    private fun update(gen: Int, f: (DeviceSyncState) -> DeviceSyncState?) = synchronized(lock) {
        if (gen == generation) f(_state.value)?.let { _state.value = it }
    }

    private suspend fun <T> storageStep(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw StorageFailure(e)
    }

    private suspend fun <T> dataStep(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceSyncDataException) {
        throw e
    } catch (e: Exception) {
        throw DataFailure(e)
    }

    private class StorageFailure(cause: Exception) : Exception(cause)

    private class DataFailure(cause: Exception) : Exception(cause)

    private class RunFailure(val reason: DeviceSyncFailure, val filesKept: Int, cause: Exception) : Exception(cause)

    companion object {
        const val DELETE_CONFIRM_TIMEOUT_MS = 2 * 60_000L

        /** The planned old song is still the same file: same tags and format, and the same path when known. */
        internal fun stillPlannedSong(current: LibrarySongInfo, planned: ReceiverSong, root: String): Boolean {
            fun norm(v: String) = v.trim().lowercase() // as SongMatcher's tag key
            if (norm(current.title) != norm(planned.title) || norm(current.artist) != norm(planned.artist) ||
                norm(current.album) != norm(planned.album)
            ) return false
            if (SyncPaths.extension(current.path) != planned.extension) return false
            return planned.relPath == null || ReceiverIndexBuilder.relativeTo(root, current.path) == planned.relPath
        }

        /**
         * Client calls are not wrapped, so a plain IOException there is the connection; local storage and data
         * steps are wrapped ([StorageFailure], [DataFailure]) before they reach this.
         */
        fun failureOf(e: Throwable): DeviceSyncFailure = when (e) {
            is StorageFailure, is SecurityException -> DeviceSyncFailure.Storage
            is DataFailure -> DeviceSyncFailure.DataApply
            is DeviceSyncAuthException -> DeviceSyncFailure.CodeMismatch
            is DeviceSyncProtocolException ->
                if (e.message == DeviceSyncServer.ERROR_VERSION_MISMATCH) DeviceSyncFailure.VersionMismatch
                else DeviceSyncFailure.Protocol
            is DeviceSyncDataException -> DeviceSyncFailure.DataApply
            is IOException -> DeviceSyncFailure.Network // SocketException, SocketTimeoutException, ConnectException, ...
            else -> DeviceSyncFailure.Unknown
        }
    }
}
