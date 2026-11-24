package de.yurtemre.sonora.shared.devicesync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the sending phone shows. */
sealed interface ServerState {
    /** Listening for a receiver. */
    data object Waiting : ServerState

    /** A receiver proved the code; [peerName] is the name from its HELLO. */
    data class Paired(val peerName: String) : ServerState

    /** Streaming [fileId]; [bytesSent] / [totalBytes] count the receiver's current FETCH as a whole. */
    data class Sending(val fileId: Int, val bytesSent: Long, val totalBytes: Long) : ServerState

    /** The receiver ended the session with DONE. The server keeps listening with a fresh code. */
    data object Done : ServerState

    /**
     * Only for a wrong code: [reason] = [DeviceSyncServer.ERROR_CODE_MISMATCH], or
     * [DeviceSyncServer.ERROR_TOO_MANY_ATTEMPTS] after the 3rd wrong code, when pairing is refused until
     * [lockedUntilMs] (clock time). The server keeps listening. Other session failures return to [Waiting].
     */
    data class Failed(val reason: String, val lockedUntilMs: Long = 0L) : ServerState

    /** [DeviceSyncServer.stop] was called; final. */
    data object Stopped : ServerState
}

/**
 * The sending phone's TCP endpoint (DSYNC-01..04): pairs with the code, answers HELLO with the [Manifest] (deflated,
 * in MANIFEST_PART frames: [ManifestTransfer]), serves
 * fingerprints and files from that manifest only.
 *
 * - **Code (D-73):** a new 6-digit code after 5 minutes (also while nobody connects: checked on every accept and
 *   once a second against [clock]) or after the 3rd handshake that failed a check ([DeviceSyncAuthException]).
 *   Disconnects, timeouts and malformed handshake data do not count. A completed session (DONE) also gets a new code.
 * - **Cool-down (I7):** a rotation caused by wrong codes also refuses pairing for [COOL_DOWN_MS] (a short, fixed
 *   10 s: a personal-use feature, so this only slows scripted guessing without getting in the way). A receiver that connects meanwhile reads a
 *   "locked" handshake message and fails with `too_many_attempts`; that never counts as an attempt.
 * - **One receiver at a time:** while a connection is being served, further connections are closed before any
 *   handshake.
 * - **After a session** the server keeps listening, so another phone can pair: [state] stays [ServerState.Done] /
 *   [ServerState.Failed] until the next receiver pairs; a session that fails after pairing returns to
 *   [ServerState.Waiting].
 * - **Read timeouts:** [pairingTimeoutMs] from accept until HELLO (a silent connection does not hold the single
 *   slot for long); [idleTimeoutMs] while waiting for the receiver's next request (it plans, fingerprints and lets
 *   the user pick files in between); [requestTimeoutMs] within a request.
 *
 * Everything runs on a scope owned by the server ([Dispatchers.IO]); [stop] cancels it, closes all sockets and
 * sets the final [ServerState.Stopped].
 */
class DeviceSyncServer(
    private val manifestProvider: suspend () -> Manifest,
    private val source: SyncFileSource,
    private val clock: () -> Long,
    private val pairingTimeoutMs: Int = PAIRING_TIMEOUT_MS,
    private val idleTimeoutMs: Int = IDLE_TIMEOUT_MS,
    private val requestTimeoutMs: Int = SOCKET_TIMEOUT_MS,
) {
    private val codeLock = Any()
    private val lockout = PairingLockout(clock)
    private val _code = MutableStateFlow(PairingCode.generate())
    private val _codeExpiresAtMs = MutableStateFlow(clock() + CODE_LIFETIME_MS)
    private val _state = MutableStateFlow<ServerState>(ServerState.Waiting)
    private val stateLock = Any()
    private var stopped = false

    val code: StateFlow<String> = _code.asStateFlow()
    val codeExpiresAtMs: StateFlow<Long> = _codeExpiresAtMs.asStateFlow()
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var serverSocket: ServerSocket? = null
    private val activeSocket = AtomicReference<Socket?>(null)

    /** Binds a free port on all interfaces, starts accepting and returns the port. */
    fun start(): Int {
        check(serverSocket == null) { "Already started" }
        val socket = ServerSocket(0)
        serverSocket = socket
        scope.launch { acceptLoop(socket) }
        scope.launch {
            while (isActive) {
                delay(CODE_CHECK_INTERVAL_MS)
                rotateCodeIfExpired()
            }
        }
        return socket.localPort
    }

    fun stop() {
        synchronized(stateLock) {
            stopped = true
            _state.value = ServerState.Stopped
        }
        scope.cancel()
        runCatching { serverSocket?.close() }
        activeSocket.getAndSet(null)?.let { runCatching { it.close() } }
    }

    private fun CoroutineScope.acceptLoop(serverSocket: ServerSocket) {
        while (isActive) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                return // closed by stop()
            }
            rotateCodeIfExpired()
            if (!activeSocket.compareAndSet(null, socket)) {
                runCatching { socket.close() } // one receiver at a time
                continue
            }
            launch {
                serveConnection(socket)?.let(::setState)
                release(socket) // after the final state, so it cannot overwrite the next session's
            }.invokeOnCompletion { release(socket) }
        }
    }

    /** Every state change goes through here; after [stop] nothing overwrites [ServerState.Stopped]. */
    private fun setState(state: ServerState) = synchronized(stateLock) {
        if (!stopped) _state.value = state
    }

    private fun release(socket: Socket) {
        runCatching { socket.close() }
        activeSocket.compareAndSet(socket, null)
    }

    /** Runs one connection; returns the state to show afterwards, or null to leave it as it is. */
    private suspend fun serveConnection(socket: Socket): ServerState? {
        val code = _code.value
        var paired = false
        try {
            socket.soTimeout = pairingTimeoutMs
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            if (isLockedOut()) {
                JpakeHandshake.refuseLocked(input, output)
                return null
            }
            val keyingMaterial = try {
                JpakeHandshake.run(input, output, code, Role.Sender, "sender")
            } catch (e: DeviceSyncAuthException) {
                return recordFailedAttempt(code)
            }
            val channel = SecureChannel(input, output, keyingMaterial, Role.Sender)
            val hello = (channel.receive() as? Frame.Hello)?.value
                ?: throw DeviceSyncProtocolException("Expected HELLO")
            if (hello.protocol != PROTOCOL_VERSION) {
                channel.send(Frame.Error(ErrorFrame(ERROR_VERSION_MISMATCH, "Protocol $PROTOCOL_VERSION required")))
                return null
            }
            paired = true
            setState(ServerState.Paired(hello.deviceName))
            socket.soTimeout = requestTimeoutMs
            val manifest = manifestProvider()
            val parts = try {
                ManifestTransfer.split(manifest)
            } catch (e: ManifestTooLargeException) {
                channel.send(Frame.Error(ErrorFrame(ERROR_LIBRARY_TOO_LARGE, "The library is too large for device sync")))
                return ServerState.Waiting
            }
            parts.forEach(channel::send)
            serve(socket, channel, manifest)
            rotateCode()
            return ServerState.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Disconnect, timeout, malformed data or a failing provider: never counts as a wrong code.
            return if (paired) ServerState.Waiting else null
        }
    }

    /** Answers requests until the receiver sends DONE. */
    private fun serve(socket: Socket, channel: SecureChannel, manifest: Manifest) {
        val byId = manifest.files.associateBy { it.id }
        while (true) {
            socket.soTimeout = idleTimeoutMs
            val frame = channel.receive()
            socket.soTimeout = requestTimeoutMs
            when (frame) {
                is Frame.FingerprintRequestFrame -> {
                    val fingerprints = frame.value.ids.distinct().mapNotNull { id ->
                        val file = byId[id] ?: return@mapNotNull null
                        try {
                            id to Fingerprint.of(file.size) { source.open(file) }
                        } catch (e: Exception) {
                            null // unreadable: the receiver treats a missing fingerprint as "different"
                        }
                    }.toMap()
                    channel.send(Frame.FingerprintsFrame(Fingerprints(fingerprints)))
                }
                is Frame.FetchFrame -> sendFiles(channel, frame.value.ids, byId)
                Frame.Done -> return
                else -> {
                    channel.send(Frame.Error(ErrorFrame(ERROR_UNEXPECTED_FRAME, "Unexpected frame")))
                    throw DeviceSyncProtocolException("Unexpected frame from receiver")
                }
            }
        }
    }

    /**
     * Per requested id, in order: FILE_START, chunks, FILE_END (SHA-256 of the bytes actually sent), or one ERROR
     * frame (unknown id, unreadable file; also after FILE_START if reading fails midway).
     * FILE_START announces the manifest size; if the source yields another length the receiver rejects the file.
     */
    private fun sendFiles(channel: SecureChannel, ids: List<Int>, byId: Map<Int, ManifestFile>) {
        val totalBytes = ids.sumOf { byId[it]?.size ?: 0L }
        var bytesSent = 0L
        for (id in ids) {
            val file = byId[id]
            if (file == null) {
                channel.send(Frame.Error(ErrorFrame(ERROR_UNKNOWN_FILE, "File $id is not in the manifest")))
                continue
            }
            val input = try {
                source.open(file)
            } catch (e: Exception) {
                channel.send(Frame.Error(ErrorFrame(ERROR_READ_FAILED, "File $id could not be read")))
                continue
            }
            try {
                channel.send(Frame.FileStartFrame(FileStart(id, file.size)))
                setState(ServerState.Sending(id, bytesSent, totalBytes))
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(FrameCodec.CHUNK)
                var readFailed = false
                while (true) {
                    val n = try {
                        input.readChunk(buffer)
                    } catch (e: Exception) {
                        readFailed = true
                        0
                    }
                    if (n == 0) break
                    digest.update(buffer, 0, n)
                    channel.send(Frame.Chunk(buffer.copyOf(n)))
                    bytesSent += n
                    setState(ServerState.Sending(id, bytesSent, totalBytes))
                }
                channel.send(
                    if (readFailed) {
                        Frame.Error(ErrorFrame(ERROR_READ_FAILED, "File $id could not be read"))
                    } else {
                        Frame.FileEndFrame(FileEnd(id, digest.digest().toHex()))
                    },
                )
            } finally {
                runCatching { input.close() }
            }
        }
    }

    /** Fills [buffer] as far as the stream allows; 0 = end of stream. */
    private fun InputStream.readChunk(buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val n = read(buffer, filled, buffer.size - filled)
            if (n < 0) break
            filled += n
        }
        return filled
    }

    private fun rotateCodeIfExpired() = synchronized(codeLock) {
        if (clock() >= _codeExpiresAtMs.value) rotateCode()
    }

    private fun isLockedOut() = lockout.isLocked()

    /**
     * Counts a failed check against [code], unless the code has been replaced meanwhile; the 3rd rotates the code
     * and starts a cool-down. Returns the state to show.
     */
    private fun recordFailedAttempt(code: String): ServerState = synchronized(codeLock) {
        if (code != _code.value) return ServerState.Failed(ERROR_CODE_MISMATCH)
        val lockedUntilMs = lockout.recordFailure() ?: return ServerState.Failed(ERROR_CODE_MISMATCH)
        rotateCode()
        ServerState.Failed(ERROR_TOO_MANY_ATTEMPTS, lockedUntilMs)
    }

    private fun rotateCode() = synchronized(codeLock) {
        val old = _code.value
        var next: String
        do next = PairingCode.generate() while (next == old)
        lockout.resetFailures()
        _codeExpiresAtMs.value = clock() + CODE_LIFETIME_MS
        _code.value = next
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val CODE_LIFETIME_MS = 5 * 60_000L
        const val MAX_FAILED_ATTEMPTS = 3
        /** Read timeout within a request (both sides). */
        const val SOCKET_TIMEOUT_MS = 30_000
        const val PAIRING_TIMEOUT_MS = 10_000
        const val IDLE_TIMEOUT_MS = 15 * 60_000

        const val ERROR_CODE_MISMATCH = "code_mismatch"
        /** Pairing refused during the cool-down after 3 wrong codes (I7). */
        const val ERROR_TOO_MANY_ATTEMPTS = "too_many_attempts"
        const val ERROR_VERSION_MISMATCH = "version_mismatch"
        const val ERROR_UNKNOWN_FILE = "unknown_file"
        const val ERROR_READ_FAILED = "read_failed"
        const val ERROR_UNEXPECTED_FRAME = "unexpected_frame"
        /** The manifest exceeds the wire caps ([ManifestTransfer]). */
        const val ERROR_LIBRARY_TOO_LARGE = "library_too_large"

        private const val CODE_CHECK_INTERVAL_MS = 1_000L
        /** Cool-down after a code rotation caused by wrong codes. */
        const val COOL_DOWN_MS = 10_000L
    }
}
