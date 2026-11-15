package de.yurtemre.sonora.devicesync

import android.content.ContentResolver
import android.net.Uri
import de.yurtemre.sonora.shared.devicesync.DeviceSyncClient
import de.yurtemre.sonora.shared.devicesync.Fingerprint
import de.yurtemre.sonora.shared.devicesync.PlanItem
import de.yurtemre.sonora.shared.devicesync.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

/** Largest id batch per `DeviceSyncClient.fingerprints` call (the client has a 30 s read timeout). */
const val FINGERPRINT_BATCH_SIZE = 200

fun <T> batched(items: List<T>, size: Int): List<List<T>> {
    require(size > 0) { "Batch size must be positive" }
    return items.chunked(size)
}

/** Asks the sender for the fingerprints of [ids], at most [FINGERPRINT_BATCH_SIZE] ids per request. */
suspend fun fetchSenderFingerprints(ids: List<Int>, request: suspend (List<Int>) -> Map<Int, String>): Map<Int, String> {
    val out = LinkedHashMap<Int, String>()
    for (batch in batched(ids, FINGERPRINT_BATCH_SIZE)) out += request(batch)
    return out
}

suspend fun fetchSenderFingerprints(client: DeviceSyncClient, ids: List<Int>): Map<Int, String> =
    fetchSenderFingerprints(ids) { client.fingerprints(it) }

/**
 * Receiver files worth fingerprinting: those whose size equals that of some [PlanItem.needsFingerprint] item
 * (the planner marks an item ambiguous exactly when a same-size file exists under another name).
 */
fun fingerprintCandidates(items: List<PlanItem>, existing: Map<String, Long>): List<String> {
    val sizes = items.filter { it.needsFingerprint }.mapTo(HashSet()) { it.file.size }
    return existing.filterValues { it in sizes }.keys.toList()
}

/**
 * DSYNC-08: an ambiguous item (same size, other name) whose sender fingerprint equals the fingerprint of a
 * receiver file becomes [Status.Present]. The fingerprint covers the size, so an equal value can only come from
 * a same-size file; [receiverFps] maps those files' relPath to their fingerprint. Everything else is unchanged.
 */
fun resolveAmbiguous(
    items: List<PlanItem>,
    senderFps: Map<Int, String>,
    receiverFps: Map<String, String>,
): List<PlanItem> {
    val known = receiverFps.values.toHashSet()
    return items.map { item ->
        val fp = senderFps[item.file.id]
        if (item.needsFingerprint && item.status != Status.Present && fp != null && fp in known) {
            item.copy(status = Status.Present, replaces = null, needsFingerprint = false)
        } else {
            item
        }
    }
}

/**
 * Fingerprints [files] (relPath -> document Uri, size and mtime) via [resolver], using and filling [cache].
 * Files that cannot be read are left out. Blocking IO runs on [Dispatchers.IO].
 */
suspend fun receiverFingerprints(
    resolver: ContentResolver,
    cache: FingerprintCache,
    files: Map<String, DestinationFile>,
): Map<String, String> = withContext(Dispatchers.IO) {
    val out = LinkedHashMap<String, String>()
    for ((rel, f) in files) {
        ensureActive()
        try {
            out[rel] = cache.getOrCompute(rel, f.size, f.lastModifiedMs) {
                Fingerprint.of(f.size) { resolver.openInputStream(f.uri) ?: throw FileNotFoundException(rel) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unreadable or shorter than listed: it simply cannot match.
        }
    }
    cache.prune(files.keys)
    cache.save()
    out
}

/**
 * `files/device_sync/fingerprints.json`: fingerprint cache keyed by `path|size|mtime` (DSYNC-08). Holds at most
 * [maxEntries] (oldest inserted dropped first); [prune] drops entries of paths that no longer exist
 * ([receiverFingerprints] prunes to the files it was given before saving).
 * A missing or corrupt file starts empty. Thread-safe.
 */
class FingerprintCache(private val file: File, private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val entries = LinkedHashMap<String, String>()
    private var loaded = false

    private fun key(path: String, size: Long, mtime: Long) = "$path|$size|$mtime"

    @Synchronized private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (file.isFile) entries += Json.decodeFromString(SERIALIZER, file.readText())
        } catch (_: Exception) {
            entries.clear()
        }
    }

    @Synchronized fun get(path: String, size: Long, mtime: Long): String? {
        ensureLoaded()
        return entries[key(path, size, mtime)]
    }

    @Synchronized fun put(path: String, size: Long, mtime: Long, fingerprint: String) {
        ensureLoaded()
        val k = key(path, size, mtime)
        entries.remove(k)
        entries[k] = fingerprint
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    /** A zero [mtime] means the provider reported none, so the entry could go stale unnoticed: not cached. */
    fun getOrCompute(path: String, size: Long, mtime: Long, compute: () -> String): String {
        if (mtime == 0L) return compute()
        return get(path, size, mtime) ?: compute().also { put(path, size, mtime, it) }
    }

    /** Keeps only entries whose path is in [livePaths]. */
    @Synchronized fun prune(livePaths: Set<String>) {
        ensureLoaded()
        entries.keys.removeAll { it.substringBeforeLast('|').substringBeforeLast('|') !in livePaths }
    }

    /** Writes the cache atomically; failures are ignored (it is only a cache). */
    @Synchronized fun save() {
        ensureLoaded()
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(Json.encodeToString(SERIALIZER, entries))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 20_000
        private val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
