package de.yurtemre.sonora.devicesync

import android.content.ContentResolver
import android.net.Uri
import de.yurtemre.sonora.shared.devicesync.AudioMeta
import de.yurtemre.sonora.shared.devicesync.DeviceSyncServer
import de.yurtemre.sonora.shared.devicesync.FileKind
import de.yurtemre.sonora.shared.devicesync.Manifest
import de.yurtemre.sonora.shared.devicesync.ManifestFile
import de.yurtemre.sonora.shared.devicesync.SyncPaths
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.DeviceSyncDataRepository
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.scan.TreeWalker
import de.yurtemre.sonora.shared.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A file found on disk during the walk: absolute [path] plus its size and modification time. */
data class WalkedFile(val path: String, val size: Long, val modifiedMs: Long)

/** Pure result of [SenderManifestBuilder.plan]: the manifest files and the absolute path behind each id. */
data class SenderPlan(val files: List<ManifestFile>, val absPathById: Map<Int, String>)

/**
 * Builds the sender manifest (DSYNC-03) and the matching [AndroidSyncFileSource]. Music root: the scan folder in
 * folder mode, otherwise the common parent directory of all library songs (a song set spanning storage volumes
 * yields a shallow root such as "/", which is fine).
 */
class SenderManifestBuilder(
    private val resolver: ContentResolver,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val repo: DeviceSyncDataRepository,
) {
    suspend fun build(deviceName: String): Pair<Manifest, AndroidSyncFileSource> = withContext(Dispatchers.IO) {
        val songs = library.songs.first()
        val s = settings.settings.first()
        val folderPath = s.scanFolderPath?.takeIf { it.isNotBlank() }
        val treeUri = if (folderPath != null) s.scanFolderUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() } else null
        val root = folderPath?.let { it.trimEnd('/').ifEmpty { "/" } } ?: commonRoot(songs.map { it.filePath })
        val walked = ArrayList<WalkedFile>()
        val uris = HashMap<String, Uri>()
        if (root != null) {
            if (treeUri != null) {
                TreeWalker.walk(resolver, treeUri) { f ->
                    walked += WalkedFile(f.path, f.size, f.lastModifiedMs)
                    uris[f.path] = f.uri
                }
            } else {
                runCatching {
                    File(root).walkTopDown().filter { it.isFile }.forEach {
                        walked += WalkedFile(it.path, it.length(), it.lastModified())
                    }
                }
            }
        }
        val plan = if (root == null) SenderPlan(emptyList(), emptyMap()) else plan(root, songs, walked)
        val entries = plan.files.associate { f ->
            val path = plan.absPathById.getValue(f.id)
            f.id to AndroidSyncFileSource.Entry(f.relPath, path, uris[path])
        }
        val manifest = Manifest(
            protocol = DeviceSyncServer.PROTOCOL_VERSION,
            deviceId = repo.deviceId(),
            deviceName = deviceName,
            bundle = repo.exportBundle(),
            files = plan.files,
        )
        manifest to AndroidSyncFileSource(resolver, entries)
    }

    companion object {
        /** Longest common parent directory of [paths] ("/" when they share nothing); null for an empty list. */
        fun commonRoot(paths: List<String>): String? {
            if (paths.isEmpty()) return null
            val dirs = paths.map { p -> p.trimEnd('/').split('/').dropLast(1).filter { it.isNotEmpty() } }
            var common = dirs.first()
            for (d in dirs.drop(1)) {
                var n = 0
                while (n < common.size && n < d.size && common[n] == d[n]) n++
                common = common.take(n)
            }
            return if (common.isEmpty()) "/" else "/" + common.joinToString("/")
        }

        /**
         * Pure inclusion logic. Audio: library songs under [root] (meta from the song; size/mtime from the walk, else
         * from the song, else skipped). Lyrics: `.lrc`/`.txt` walked files next to an included audio file with the
         * same base name. Covers: walked images with a cover file name. Only paths that pass [SyncPaths.sanitize]
         * unchanged and have a [SyncPaths.kindOf] are kept; ids are indexes in the relPath-sorted list.
         */
        fun plan(root: String, songs: List<Song>, walked: List<WalkedFile>): SenderPlan {
            val prefix = if (root.endsWith("/")) root else "$root/"
            fun rel(path: String): String? {
                if (!path.startsWith(prefix)) return null
                val r = path.substring(prefix.length)
                return if (SyncPaths.sanitize(r) == r) r else null
            }
            val walkedByPath = LinkedHashMap<String, WalkedFile>()
            walked.forEach { walkedByPath.putIfAbsent(it.path, it) }

            class Item(val abs: String, val rel: String, val size: Long, val mod: Long, val kind: FileKind, val audio: AudioMeta?)
            val items = LinkedHashMap<String, Item>()
            val audioBases = HashSet<String>() // "<dir>\u0000<base>" of included audio files
            for (song in songs) {
                val r = rel(song.filePath) ?: continue
                if (SyncPaths.kindOf(r) != FileKind.Audio) continue
                val w = walkedByPath[song.filePath]
                val size = w?.size?.takeIf { it > 0 } ?: song.fileSize?.takeIf { it > 0 } ?: continue
                val mod = w?.modifiedMs ?: song.lastModifiedMs ?: 0L
                items[song.filePath] = Item(
                    song.filePath, r, size, mod, FileKind.Audio,
                    AudioMeta(song.title, song.artist, song.album, song.durationMs),
                )
                audioBases += SyncPaths.parent(r) + "\u0000" + SyncPaths.baseName(r).substringBeforeLast('.')
            }
            for (w in walkedByPath.values) {
                if (w.path in items || w.size <= 0) continue
                val r = rel(w.path) ?: continue
                val kind = SyncPaths.kindOf(r) ?: continue
                val keep = when (kind) {
                    FileKind.Audio -> false
                    FileKind.Lyrics ->
                        (SyncPaths.parent(r) + "\u0000" + SyncPaths.baseName(r).substringBeforeLast('.')) in audioBases
                    FileKind.Cover -> true
                }
                if (keep) items[w.path] = Item(w.path, r, w.size, w.modifiedMs, kind, null)
            }
            val sorted = items.values.sortedBy { it.rel }
            val files = sorted.mapIndexed { i, it -> ManifestFile(i, it.rel, it.size, it.mod, it.kind, it.audio) }
            return SenderPlan(files, sorted.mapIndexed { i, it -> i to it.abs }.toMap())
        }
    }
}
