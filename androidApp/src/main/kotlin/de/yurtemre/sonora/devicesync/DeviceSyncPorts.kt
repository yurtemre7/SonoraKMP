package de.yurtemre.sonora.devicesync

import android.content.IntentSender
import android.net.Uri
import de.yurtemre.sonora.shared.devicesync.FetchResult
import de.yurtemre.sonora.shared.devicesync.Hello
import de.yurtemre.sonora.shared.devicesync.Manifest
import de.yurtemre.sonora.shared.devicesync.ServerState
import de.yurtemre.sonora.shared.devicesync.SyncFileSink
import de.yurtemre.sonora.shared.devicesync.TransferProgress
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Small seams of [DeviceSyncController], so its state machine can be tested with fakes. The Android
 * implementations live in AndroidDeviceSyncPorts.kt; every blocking call they make runs on Dispatchers.IO.
 */

/** A running sender: server + advertisement. [stop] ends both (idempotent). */
interface SenderSession {
    val code: StateFlow<String>
    val codeExpiresAtMs: StateFlow<Long>
    val state: StateFlow<ServerState>
    /** `IP:port` for manual entry, or null when no IPv4 address is known. */
    val address: String?
    fun stop()
}

fun interface SenderSessionFactory {
    /** Builds the manifest, starts the server and advertises it. Throws when the sender cannot start. */
    suspend fun start(deviceName: String): SenderSession
}

/** Receiver side of one connection (see `DeviceSyncClient`). */
interface SyncClient {
    suspend fun connect(host: String, port: Int, code: String, hello: Hello): Manifest
    suspend fun fingerprints(ids: List<Int>): Map<Int, String>
    suspend fun fetch(ids: List<Int>, sink: SyncFileSink, onProgress: (TransferProgress) -> Unit): FetchResult
    suspend fun close()
}

fun interface SyncClientFactory {
    fun create(): SyncClient
}

fun interface SenderDiscoverySource {
    /** Current senders; fails with an IOException when discovery cannot run. */
    fun discover(): Flow<List<DiscoveredSender>>
}

/** A [SyncFileSink] that reports the real final relPath of every committed file id. */
interface CommittingSyncFileSink : SyncFileSink {
    val committed: Map<Int, String>
}

/** The receiver's destination folder and its contents (DSYNC-08..11). */
interface ReceiverStorage {
    /** The scan folder when it is writable, else null. */
    suspend fun defaultDestination(): Uri?

    /** `EXTRA_INITIAL_URI` for the folder picker. */
    suspend fun pickerInitialUri(): Uri

    /** Filesystem path of [destination] (null when it has none, e.g. a non-external-storage tree). */
    fun rootPath(destination: Uri): String?

    suspend fun scan(destination: Uri): ReceiverScan

    /** Fingerprints of [relPaths] (files of [scan]); unreadable files are left out. */
    suspend fun fingerprints(scan: ReceiverScan, relPaths: List<String>): Map<String, String>

    /** Null = unknown. */
    suspend fun freeBytes(destination: Uri): Long?

    fun openSink(destination: Uri): CommittingSyncFileSink
}

/** Library access after a transfer. */
interface ReceiverLibrary {
    /** Makes the system media index aware of new files (absolute paths) before [sync]. */
    suspend fun indexNewFiles(paths: List<String>)

    /** The library scan (`LibraryRepository.sync()`), waiting for it to finish. */
    suspend fun sync(): SyncOutcome

    suspend fun songIdByPath(path: String): Long?

    /** The song's current library row, re-read now. */
    suspend fun song(songId: Long): LibrarySongInfo?
}

data class LibrarySongInfo(val path: String, val title: String, val artist: String, val album: String)

/** Deletes the old files of Replace items (DSYNC-12). */
fun interface ReplacedFileDeleter {
    /**
     * Deletes the files at [paths] under the rules of the current library mode and returns the paths actually
     * deleted. When the system must confirm (MediaStore mode, API 30+), [confirm] is called once with one batched
     * request and returns whether the user allowed it.
     */
    suspend fun delete(paths: List<String>, destination: Uri, confirm: suspend (IntentSender) -> Boolean): Set<String>
}

/** This phone's display name for HELLO and the NSD advertisement. */
fun interface DeviceNameSource {
    fun deviceName(): String
}

/** Keeps the foreground service running while a transfer needs it. */
fun interface DeviceSyncServiceStarter {
    fun ensureRunning()
}
