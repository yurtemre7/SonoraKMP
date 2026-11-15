package de.yurtemre.sonora.shared.devicesync

import de.yurtemre.sonora.shared.devicesync.DeviceSyncServer.Companion.ERROR_VERSION_MISMATCH
import de.yurtemre.sonora.shared.devicesync.DeviceSyncServer.Companion.PROTOCOL_VERSION
import de.yurtemre.sonora.shared.devicesync.DeviceSyncServer.Companion.SOCKET_TIMEOUT_MS
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transfer progress of one [DeviceSyncClient.fetch]: the current file ([fileBytes] of [fileSize]), the whole
 * request ([totalBytes] of [totalSize], [filesDone] of [filesTotal], failed files included) and the rate over the
 * last 2 seconds.
 */
data class TransferProgress(
    val fileId: Int,
    val fileBytes: Long,
    val fileSize: Long,
    val totalBytes: Long,
    val totalSize: Long,
    val filesDone: Int,
    val filesTotal: Int,
    val bytesPerSecond: Long,
)

/** [committed] in request order; [failed] maps an id to its reason (an error frame code, `size_mismatch`, …). */
data class FetchResult(val committed: List<Int>, val failed: Map<Int, String>)

/**
 * The receiving phone's side of a device sync session (DSYNC-02..04, DSYNC-11). One instance per connection:
 * [connect], then any number of [fingerprints] / [fetch] calls (one at a time), then [close].
 *
 * Blocking IO runs on [Dispatchers.IO]; every read has the [readTimeoutMs] timeout. Cancelling a call closes the
 * socket so a blocked read ends at once. After any failed call (or cancellation) or [close] the session is over:
 * later calls throw [IllegalStateException].
 */
class DeviceSyncClient(
    private val readTimeoutMs: Int = SOCKET_TIMEOUT_MS,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var channel: SecureChannel? = null
    @Volatile private var files: Map<Int, ManifestFile> = emptyMap()
    /** Why the session is over ("closed" / "failed"), or null while usable. */
    @Volatile private var ended: String? = null

    /**
     * Connects (10 s timeout), pairs with [code] (wrong code → [DeviceSyncAuthException]), sends [hello] and
     * returns the sender's manifest. Either side on another protocol version →
     * [DeviceSyncProtocolException] with message `version_mismatch`.
     */
    suspend fun connect(host: String, port: Int, code: String, hello: Hello): Manifest {
        check(socket == null) { "DeviceSyncClient is single-use" }
        val socket = Socket().also { socket = it }
        return blocking(socket) {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val keyingMaterial = JpakeHandshake.run(input, output, code, Role.Receiver, hello.deviceId)
            val channel = SecureChannel(input, output, keyingMaterial, Role.Receiver)
            channel.send(Frame.Hello(hello))
            val manifest = when (val frame = channel.receive()) {
                is Frame.ManifestFrame -> frame.value
                is Frame.Error -> throw DeviceSyncProtocolException(frame.value.code)
                else -> throw DeviceSyncProtocolException("Expected MANIFEST")
            }
            if (manifest.protocol != PROTOCOL_VERSION) throw DeviceSyncProtocolException(ERROR_VERSION_MISMATCH)
            files = manifest.files.associateBy { it.id }
            this.channel = channel
            manifest
        }
    }

    /** Fingerprints (see [Fingerprint]) for [ids]; ids the sender does not know or cannot read are absent. */
    suspend fun fingerprints(ids: List<Int>): Map<Int, String> {
        val channel = connectedChannel()
        return blocking(socket!!) {
            channel.send(Frame.FingerprintRequestFrame(FingerprintRequest(ids)))
            when (val frame = channel.receive()) {
                is Frame.FingerprintsFrame -> frame.value.byId
                is Frame.Error -> throw DeviceSyncProtocolException(frame.value.code)
                else -> throw DeviceSyncProtocolException("Expected FINGERPRINTS")
            }
        }
    }

    /**
     * Downloads [ids] in order into [sink]. Each file is committed only when its size matches the manifest and
     * FILE_START and its SHA-256 matches FILE_END; otherwise it is aborted and listed in [FetchResult.failed], and
     * the next file follows. [onProgress] runs on the IO thread at most about 10 times a second, plus once at the
     * end. Cancellation (or a lost connection) aborts the file in progress and ends the session.
     */
    suspend fun fetch(ids: List<Int>, sink: SyncFileSink, onProgress: (TransferProgress) -> Unit): FetchResult {
        val channel = connectedChannel()
        return blocking(socket!!) { receiveFiles(channel, ids, sink, onProgress) }
    }

    /** Sends DONE (best effort, also from a cancelled coroutine) and closes the connection. */
    suspend fun close() {
        val channel = channel
        this.channel = null
        if (ended == null) ended = "closed"
        withContext(NonCancellable + Dispatchers.IO) {
            if (channel != null) runCatching { channel.send(Frame.Done) }
            runCatching { socket?.close() }
        }
    }

    private fun connectedChannel(): SecureChannel {
        ended?.let { throw IllegalStateException("DeviceSyncClient session is $it") }
        return checkNotNull(channel) { "Not connected" }
    }

    /** Ends the session after a failed call: the stream state is unknown, so nothing more may be sent or read. */
    private fun fail() {
        if (ended == null) ended = "failed"
        channel = null
        runCatching { socket?.close() }
    }

    private fun receiveFiles(
        channel: SecureChannel,
        ids: List<Int>,
        sink: SyncFileSink,
        onProgress: (TransferProgress) -> Unit,
    ): FetchResult {
        val known = files
        val meter = ProgressMeter(ids.size, ids.sumOf { known[it]?.size ?: 0L }, onProgress)
        val committed = mutableListOf<Int>()
        val failed = linkedMapOf<Int, String>()
        channel.send(Frame.FetchFrame(Fetch(ids)))
        for (id in ids) {
            // The sender answers each id, in order, with FILE_START … FILE_END or with one ERROR frame.
            when (val frame = channel.receive()) {
                is Frame.Error -> failed[id] = frame.value.code
                is Frame.FileStartFrame -> {
                    val file = known[id]
                    if (frame.value.id != id || file == null) {
                        throw DeviceSyncProtocolException("Unexpected FILE_START for ${frame.value.id}")
                    }
                    val failure = receiveFile(channel, file, frame.value.size, sink) { chunkSize, fileBytes ->
                        meter.chunk(id, chunkSize, fileBytes, file.size, committed.size + failed.size)
                    }
                    if (failure == null) committed += id else failed[id] = failure
                }
                else -> throw DeviceSyncProtocolException("Expected FILE_START")
            }
        }
        meter.finish(ids.lastOrNull() ?: -1, committed.size + failed.size)
        return FetchResult(committed, failed)
    }

    /** Receives one file after its FILE_START; returns null when committed, else the failure reason. */
    private fun receiveFile(
        channel: SecureChannel,
        file: ManifestFile,
        announcedSize: Long,
        sink: SyncFileSink,
        onChunk: (chunkSize: Int, fileBytes: Long) -> Unit,
    ): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        var output: OutputStream? = null
        var writeFailed = false
        var settled = false
        try {
            try {
                output = sink.begin(file)
            } catch (e: Exception) {
                writeFailed = true
            }
            while (true) {
                when (val frame = channel.receive()) {
                    is Frame.Chunk -> {
                        received += frame.bytes.size
                        if (received > maxOf(file.size, announcedSize)) {
                            throw DeviceSyncProtocolException("File ${file.id} is larger than announced")
                        }
                        digest.update(frame.bytes)
                        // Past the expected size the file is rejected anyway: stop writing, keep reading the frames.
                        if (!writeFailed && received <= file.size) {
                            try {
                                output!!.write(frame.bytes)
                            } catch (e: Exception) {
                                writeFailed = true
                            }
                        }
                        onChunk(frame.bytes.size, received)
                    }
                    is Frame.FileEndFrame -> {
                        if (frame.value.id != file.id) throw DeviceSyncProtocolException("Unexpected FILE_END")
                        if (!closeQuietly(output)) writeFailed = true
                        val hex = digest.digest().toHex()
                        val failure = when {
                            writeFailed -> FAILURE_WRITE
                            received != file.size || received != announcedSize -> FAILURE_SIZE
                            !hex.equals(frame.value.sha256Hex, ignoreCase = true) -> FAILURE_CHECKSUM
                            else -> null
                        }
                        settled = true
                        if (failure != null) {
                            abortQuietly(sink, file)
                            return failure
                        }
                        return try {
                            sink.commit(file, hex)
                            null
                        } catch (e: Exception) {
                            abortQuietly(sink, file)
                            FAILURE_WRITE
                        }
                    }
                    is Frame.Error -> {
                        closeQuietly(output)
                        settled = true
                        abortQuietly(sink, file)
                        return frame.value.code
                    }
                    else -> throw DeviceSyncProtocolException("Unexpected frame during a file")
                }
            }
        } catch (e: Throwable) {
            if (!settled) {
                closeQuietly(output)
                abortQuietly(sink, file)
            }
            throw e
        }
    }

    private fun closeQuietly(output: OutputStream?): Boolean =
        try {
            output?.close()
            true
        } catch (e: Exception) {
            false
        }

    /** A failing abort must not end the fetch: the file is already listed as failed. */
    private fun abortQuietly(sink: SyncFileSink, file: ManifestFile) {
        try {
            sink.abort(file)
        } catch (e: Exception) {
            // nothing more to do for this file
        }
    }

    /**
     * Runs blocking [block] on [Dispatchers.IO]. If the calling coroutine is cancelled meanwhile, the socket is
     * closed so a blocked read or write fails at once; the call then ends with the cancellation. Any failure ends
     * the session ([fail]).
     */
    private suspend fun <T> blocking(socket: Socket, block: () -> T): T = try {
        ioClosingOnCancel(socket, block)
    } catch (e: Throwable) {
        fail()
        throw e
    }

    private suspend fun <T> ioClosingOnCancel(socket: Socket, block: () -> T): T = withContext(Dispatchers.IO) {
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
        } catch (e: Exception) {
            ensureActive()
            throw e
        } finally {
            closer.cancel()
        }
    }

    /** Reports progress at most every [MIN_INTERVAL_MS]; the rate is measured over the last [WINDOW_MS]. */
    private class ProgressMeter(
        private val filesTotal: Int,
        private val totalSize: Long,
        private val onProgress: (TransferProgress) -> Unit,
    ) {
        private val samples = ArrayDeque<Pair<Long, Long>>() // (time ms, total bytes)
        private var totalBytes = 0L
        private var lastEmitMs = Long.MIN_VALUE
        private var last: TransferProgress? = null

        init {
            samples.addLast(nowMs() to 0L)
        }

        fun chunk(fileId: Int, chunkSize: Int, fileBytes: Long, fileSize: Long, filesDone: Int) {
            totalBytes += chunkSize
            val now = nowMs()
            samples.addLast(now to totalBytes)
            while (samples.size > 2 && samples[1].first <= now - WINDOW_MS) samples.removeFirst()
            val (startMs, startBytes) = samples.first()
            val rate = if (now > startMs) (totalBytes - startBytes) * 1000 / (now - startMs) else 0L
            val progress = TransferProgress(fileId, fileBytes, fileSize, totalBytes, totalSize, filesDone, filesTotal, rate)
            last = progress
            if (now - lastEmitMs >= MIN_INTERVAL_MS) {
                lastEmitMs = now
                onProgress(progress)
            }
        }

        /** The closing report, so the caller always sees the final counts. */
        fun finish(lastId: Int, filesDone: Int) {
            onProgress(
                last?.copy(filesDone = filesDone)
                    ?: TransferProgress(lastId, 0, 0, totalBytes, totalSize, filesDone, filesTotal, 0),
            )
        }

        private fun nowMs() = System.nanoTime() / 1_000_000

        private companion object {
            const val MIN_INTERVAL_MS = 100L
            const val WINDOW_MS = 2_000L
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000

        const val FAILURE_SIZE = "size_mismatch"
        const val FAILURE_CHECKSUM = "checksum_mismatch"
        const val FAILURE_WRITE = "write_failed"
    }
}
