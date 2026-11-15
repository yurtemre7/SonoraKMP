package de.yurtemre.sonora.devicesync

import android.content.ContentResolver
import android.net.Uri
import de.yurtemre.sonora.shared.devicesync.ReceiverIndex
import de.yurtemre.sonora.shared.devicesync.ReceiverSong
import de.yurtemre.sonora.shared.devicesync.SyncPaths
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.platform.TreeUriPaths
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.scan.TreeWalker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A file found in the destination tree. */
data class DestinationFile(val uri: Uri, val size: Long, val lastModifiedMs: Long)

/** [index] feeds `TransferPlanner`; [files] (relPath -> file) feeds receiver fingerprinting. */
data class ReceiverScan(val index: ReceiverIndex, val files: Map<String, DestinationFile>)

/** Builds the receiver's view of the destination for planning (DSYNC-08). */
class ReceiverIndexBuilder(
    private val resolver: ContentResolver,
    private val library: LibraryRepository,
) {
    suspend fun build(destination: Uri): ReceiverScan = withContext(Dispatchers.IO) {
        val root = TreeUriPaths.treeUriToPath(destination)?.trimEnd('/')
        val files = LinkedHashMap<String, DestinationFile>()
        if (root != null) {
            TreeWalker.walk(resolver, destination) { f ->
                val rel = relativeTo(root, f.path)
                if (rel != null && !rel.endsWith(PART_SUFFIX)) files.putIfAbsent(rel, DestinationFile(f.uri, f.size, f.lastModifiedMs))
            }
        }
        val songs = library.songs.first().map { toReceiverSong(it, root) }
        ReceiverScan(ReceiverIndex(files.mapValues { it.value.size }, songs), files)
    }

    companion object {
        const val PART_SUFFIX = ".sonora-part"

        /** [path] relative to [root] (checked with a `root/` prefix), or null when outside it or equal to it. */
        fun relativeTo(root: String, path: String): String? {
            val prefix = root.trimEnd('/') + "/"
            return if (path.startsWith(prefix) && path.length > prefix.length) path.substring(prefix.length) else null
        }

        fun toReceiverSong(song: Song, root: String?): ReceiverSong = ReceiverSong(
            songId = song.id,
            relPath = root?.let { relativeTo(it, song.filePath) },
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            size = song.fileSize,
            extension = SyncPaths.extension(song.filePath),
        )
    }
}
