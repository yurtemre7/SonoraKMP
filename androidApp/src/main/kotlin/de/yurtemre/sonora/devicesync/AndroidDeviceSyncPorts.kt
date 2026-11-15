package de.yurtemre.sonora.devicesync

import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import de.yurtemre.sonora.shared.devicesync.DeviceSyncClient
import de.yurtemre.sonora.shared.devicesync.DeviceSyncServer
import de.yurtemre.sonora.shared.devicesync.FetchResult
import de.yurtemre.sonora.shared.devicesync.Hello
import de.yurtemre.sonora.shared.devicesync.Manifest
import de.yurtemre.sonora.shared.devicesync.ServerState
import de.yurtemre.sonora.shared.devicesync.SyncFileSink
import de.yurtemre.sonora.shared.devicesync.TransferProgress
import de.yurtemre.sonora.shared.platform.TreeUriPaths
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import de.yurtemre.sonora.shared.settings.SettingsRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Sender: manifest + [DeviceSyncServer] + NSD advertisement (DSYNC-01..04). */
class AndroidSenderSessionFactory(
    private val builder: SenderManifestBuilder,
    private val discovery: DeviceSyncDiscovery,
    private val clock: Clock,
) : SenderSessionFactory {
    override suspend fun start(deviceName: String): SenderSession {
        val (manifest, source) = builder.build(deviceName)
        val server = DeviceSyncServer({ manifest }, source, clock::nowMs)
        val port = withContext(Dispatchers.IO) { server.start() }
        val advertisement = try {
            discovery.advertise(port, manifest.deviceId, deviceName)
        } catch (_: IOException) {
            null // mDNS unavailable: the receiver can still enter IP:port by hand
        }
        val ip = withContext(Dispatchers.IO) { discovery.localIpv4() }
        return object : SenderSession {
            override val code: StateFlow<String> = server.code
            override val codeExpiresAtMs: StateFlow<Long> = server.codeExpiresAtMs
            override val state: StateFlow<ServerState> = server.state
            override val address: String? = ip?.let { "$it:$port" }
            override fun stop() {
                runCatching { advertisement?.close() }
                server.stop()
            }
        }
    }
}

/** [SyncClient] over a fresh [DeviceSyncClient]. */
class DeviceSyncClientAdapter(private val client: DeviceSyncClient = DeviceSyncClient()) : SyncClient {
    override suspend fun connect(host: String, port: Int, code: String, hello: Hello): Manifest =
        client.connect(host, port, code, hello)

    override suspend fun fingerprints(ids: List<Int>): Map<Int, String> = client.fingerprints(ids)

    override suspend fun fetch(ids: List<Int>, sink: SyncFileSink, onProgress: (TransferProgress) -> Unit): FetchResult =
        client.fetch(ids, sink, onProgress)

    override suspend fun close() = client.close()
}

class AndroidSenderDiscovery(private val discovery: DeviceSyncDiscovery) : SenderDiscoverySource {
    override fun discover(): Flow<List<DiscoveredSender>> = discovery.discover()
}

/** DSYNC-08..11 on SAF: destination from the scan folder, index, fingerprints, free space, sink. */
class AndroidReceiverStorage(
    private val context: Context,
    private val settings: SettingsRepository,
    private val folder: DestinationFolder,
    private val indexBuilder: ReceiverIndexBuilder,
    private val cache: FingerprintCache,
) : ReceiverStorage {
    private suspend fun scanFolderUri() = settings.settings.first().scanFolderUri

    override suspend fun defaultDestination(): Uri? {
        val scan = scanFolderUri()
        return withContext(Dispatchers.IO) { folder.defaultDestination(scan) }
    }

    override suspend fun pickerInitialUri(): Uri = folder.initialUri(scanFolderUri())

    override fun rootPath(destination: Uri): String? = TreeUriPaths.treeUriToPath(destination)

    override suspend fun scan(destination: Uri): ReceiverScan = indexBuilder.build(destination)

    override suspend fun fingerprints(scan: ReceiverScan, relPaths: List<String>): Map<String, String> {
        val wanted = relPaths.toHashSet()
        return receiverFingerprints(context.contentResolver, cache, scan.files.filterKeys { it in wanted })
    }

    override suspend fun freeBytes(destination: Uri): Long? = withContext(Dispatchers.IO) { folder.freeBytes(destination) }

    override fun openSink(destination: Uri): CommittingSyncFileSink = SafSyncFileSink(context, destination)
}

/** Library access after a transfer: media index, rescan, ids by path. */
class AndroidReceiverLibrary(
    private val context: Context,
    private val library: LibraryRepository,
) : ReceiverLibrary {
    override suspend fun indexNewFiles(paths: List<String>) = scanMediaFiles(context, paths)

    override suspend fun sync(): SyncOutcome = library.sync()

    override suspend fun songIdByPath(path: String): Long? = library.getSongByPath(path)?.id

    override suspend fun songPath(songId: Long): String? = library.getSong(songId)?.filePath
}

/**
 * DSYNC-12 deletion of replaced files. Folder mode: `DocumentsContract.deleteDocument` through the destination or
 * scan tree that contains the file (files outside both are kept). MediaStore mode: one batched
 * `MediaStore.createDeleteRequest` on API 30+, else `File.delete` when the file is writable. Only [delete]'s
 * paths are ever touched.
 */
class AndroidReplacedFileDeleter(
    private val context: Context,
    private val settings: SettingsRepository,
) : ReplacedFileDeleter {
    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun delete(
        paths: List<String>,
        destination: Uri,
        confirm: suspend (IntentSender) -> Boolean,
    ): Set<String> {
        if (paths.isEmpty()) return emptySet()
        val s = settings.settings.first()
        val deleted = if (!s.scanFolderPath.isNullOrBlank()) {
            val scanTree = s.scanFolderUri?.takeIf { it.isNotBlank() }?.let { runCatching { it.toUri() }.getOrNull() }
            withContext(Dispatchers.IO) { deleteViaSaf(paths, listOfNotNull(destination, scanTree).distinct()) }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return deleteViaMediaStore(paths, confirm) // MediaStore removes its own rows
        } else {
            withContext(Dispatchers.IO) { paths.filterTo(HashSet()) { p -> File(p).let { it.canWrite() && it.delete() } } }
        }
        // Let MediaStore forget the deleted files before the library rescans.
        scanMediaFiles(context, deleted.toList())
        return deleted
    }

    private fun deleteViaSaf(paths: List<String>, trees: List<Uri>): Set<String> {
        val out = HashSet<String>()
        for (path in paths) {
            for (tree in trees) {
                val root = TreeUriPaths.treeUriToPath(tree) ?: continue
                if (ReceiverIndexBuilder.relativeTo(root, path) == null) continue
                val docId = TreeUriPaths.pathToDocId(path) ?: continue
                val ok = try {
                    DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId))
                } catch (_: Exception) {
                    false // no write grant on this tree, or already gone: keep both
                }
                if (ok) {
                    out += path
                    break
                }
            }
        }
        return out
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun deleteViaMediaStore(paths: List<String>, confirm: suspend (IntentSender) -> Boolean): Set<String> {
        val uris = withContext(Dispatchers.IO) { paths.mapNotNull { p -> mediaStoreUri(p)?.let { p to it } } }
        if (uris.isEmpty()) return emptySet()
        val request = MediaStore.createDeleteRequest(resolver, uris.map { it.second }).intentSender
        if (!confirm(request)) return emptySet()
        return withContext(Dispatchers.IO) { uris.filter { (p, _) -> mediaStoreUri(p) == null }.mapTo(HashSet()) { it.first } }
    }

    private fun mediaStoreUri(path: String): Uri? {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        return try {
            resolver.query(base, arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.Audio.Media.DATA} = ?", arrayOf(path), null)
                ?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(base, c.getLong(0).toString()) else null }
        } catch (_: Exception) {
            null
        }
    }
}

/** `Settings.Global.DEVICE_NAME` (API 25+), else the model name. */
class AndroidDeviceName(private val context: Context) : DeviceNameSource {
    override fun deviceName(): String {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        } else {
            null
        }
        return name?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}

/** Asks MediaStore to (re)index [paths] and waits for it (at most [MEDIA_SCAN_TIMEOUT_MS]). */
suspend fun scanMediaFiles(context: Context, paths: List<String>) {
    if (paths.isEmpty()) return
    withTimeoutOrNull(MEDIA_SCAN_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val left = AtomicInteger(paths.size)
            MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null) { _, _ ->
                if (left.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
            }
        }
    }
}

private const val MEDIA_SCAN_TIMEOUT_MS = 60_000L
