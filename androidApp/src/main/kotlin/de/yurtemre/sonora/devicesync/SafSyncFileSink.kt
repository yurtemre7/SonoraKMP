package de.yurtemre.sonora.devicesync

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import de.yurtemre.sonora.shared.devicesync.ManifestFile
import de.yurtemre.sonora.shared.devicesync.SyncFileSink
import de.yurtemre.sonora.shared.devicesync.SyncPaths
import de.yurtemre.sonora.shared.scan.TreeWalker
import java.io.IOException
import java.io.OutputStream

/** Pure name logic of [SafSyncFileSink]. */
object SyncNames {
    const val PART_SUFFIX = ".sonora-part"

    /**
     * Final path to try for the [attempt]th time: [relPath] itself for attempt 1, else
     * [SyncPaths.collisionName]. A candidate must pass [SyncPaths.sanitize] unchanged; when the suffix makes the
     * file name exceed 255 UTF-8 bytes, the base name (not the ` (n)` suffix or the extension) is cut at a code
     * point boundary until it fits. Null when [relPath] is unsafe or nothing of the base name is left.
     */
    fun candidate(relPath: String, attempt: Int): String? {
        if (SyncPaths.sanitize(relPath) != relPath) return null
        val plain = SyncPaths.collisionName(relPath, attempt)
        if (SyncPaths.sanitize(plain) == plain) return plain
        val name = SyncPaths.baseName(relPath)
        val dot = name.lastIndexOf('.')
        val stem = if (dot <= 0) name else name.substring(0, dot)
        val ext = if (dot <= 0) "" else name.substring(dot)
        val suffix = if (attempt >= 2) " ($attempt)" else ""
        val room = SyncPaths.MAX_SEGMENT_BYTES - (suffix + ext).encodeToByteArray().size
        val cut = truncateUtf8(stem, room).trimEnd('.', ' ')
        if (cut.isEmpty()) return null
        val parent = SyncPaths.parent(relPath)
        val result = (if (parent.isEmpty()) "" else "$parent/") + cut + suffix + ext
        return result.takeIf { SyncPaths.sanitize(it) == it }
    }

    /** `<name>.sonora-part`, with [name] truncated (code point boundary) so the result stays within 255 bytes. */
    fun partName(name: String): String {
        val room = SyncPaths.MAX_SEGMENT_BYTES - PART_SUFFIX.length
        return truncateUtf8(name, room) + PART_SUFFIX
    }

    private fun truncateUtf8(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            if (bytes + n > maxBytes) break
            bytes += n
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }
}

/**
 * Writes received files into the SAF tree [treeUri] (DSYNC-11): `<name>.sonora-part` first, renamed on [commit];
 * existing files are never overwritten or deleted (a clash gets ` (2)` ... ` (99)`). The client calls begin /
 * commit / abort one file at a time from its blocking fetch loop; methods are synchronized regardless.
 *
 * [committed] maps each committed file id to the relPath it actually ended up at (collision suffix, provider
 * auto-rename) for library rescan, stats relink and Replace deletion. All blocking; call on IO.
 */
class SafSyncFileSink(context: android.content.Context, private val treeUri: Uri) : SyncFileSink {
    private val resolver: ContentResolver = context.contentResolver
    private val rootDocUri: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    /** [actualRel] is the directory's real path below the root (real display names, which can differ from the sender's spelling). */
    private class Dir(val uri: Uri, val actualRel: String)

    private val dirs = HashMap<String, Dir>().apply { put("", Dir(rootDocUri, "")) }
    private val open = HashMap<Int, Part>()
    private val done = LinkedHashMap<Int, String>()
    private var cleaned = false

    private class Part(val relPath: String, val parent: Dir, val partUri: Uri)

    /** Snapshot of file id -> actual final relPath (real directory and file names) of every committed file. */
    val committed: Map<Int, String> @Synchronized get() = LinkedHashMap(done)

    /**
     * Deletes every `*.sonora-part` under the tree (left over from an interrupted run). Runs automatically before
     * the first [begin]. It matches by suffix only, as DSYNC-11 specifies the part-file name.
     */
    @Synchronized fun cleanupLeftovers() {
        cleaned = true
        TreeWalker.walk(resolver, treeUri) { f ->
            if (f.path.endsWith(SyncNames.PART_SUFFIX)) runCatching { DocumentsContract.deleteDocument(resolver, f.uri) }
        }
    }

    @Synchronized override fun begin(file: ManifestFile): OutputStream {
        if (!cleaned) cleanupLeftovers()
        val rel = SyncPaths.sanitize(file.relPath)
        if (rel == null || rel != file.relPath) throw IOException("unsafe_path")
        if (SyncPaths.kindOf(rel) == null) throw IOException("type_not_allowed")
        abort(file) // a repeated begin must not leak the previous part file
        val parent = ensureDir(SyncPaths.parent(rel))
        val partUri = DocumentsContract.createDocument(
            resolver, parent.uri, "application/octet-stream", SyncNames.partName(SyncPaths.baseName(rel)),
        ) ?: throw IOException("create_failed")
        try {
            // The provider may have auto-renamed the part file; the Uri we got back is what counts from here on.
            val stream = resolver.openOutputStream(partUri, "w") ?: throw IOException("open_failed")
            open[file.id] = Part(rel, parent, partUri)
            return stream
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, partUri) }
            throw if (e is IOException) e else IOException("open_failed", e)
        }
    }

    /**
     * Picks the first free name among [SyncNames.candidate] 1..99 and renames the part file to it. The directory is
     * listed once; the existence probe is the main guard against overwriting. `renameDocument` is not atomic with
     * the probe, so we also rely on providers refusing (null/throw) a rename onto an existing name, as
     * ExternalStorageProvider does; when it does, the next candidate is tried. Provider semantics for renaming
     * onto an existing name are otherwise undefined, which is a residual risk we accept.
     */
    @Synchronized override fun commit(file: ManifestFile, sha256Hex: String) {
        val part = open[file.id] ?: throw IOException("not_begun")
        val taken = listNames(part.parent.uri)
        for (attempt in 1..MAX_ATTEMPTS) {
            val cand = SyncNames.candidate(part.relPath, attempt) ?: continue
            val name = SyncPaths.baseName(cand)
            if (name.lowercase() in taken) continue
            val renamed = try {
                DocumentsContract.renameDocument(resolver, part.partUri, name)
            } catch (_: Exception) {
                null
            }
            if (renamed == null) { taken += name.lowercase(); continue } // lost a race or refused: next candidate
            // A provider may suffix the name on its own; accept that but record what the file is really called.
            val actualName = displayName(renamed) ?: name
            val dir = part.parent.actualRel
            done[file.id] = if (dir.isEmpty()) actualName else "$dir/$actualName"
            open.remove(file.id)
            return
        }
        throw IOException("no_free_name")
    }

    @Synchronized override fun abort(file: ManifestFile) {
        val part = open.remove(file.id) ?: return
        runCatching { DocumentsContract.deleteDocument(resolver, part.partUri) }
    }

    /** The directory [relDir] below the root, creating missing levels (cached per relDir). */
    private fun ensureDir(relDir: String): Dir {
        dirs[relDir]?.let { return it }
        val parent = ensureDir(SyncPaths.parent(relDir))
        val name = SyncPaths.baseName(relDir)
        val existing = findChild(parent.uri, name)
        val dir = if (existing != null && existing.isDir) {
            Dir(existing.uri, join(parent.actualRel, existing.name))
        } else {
            // Absent, or a plain file holds the name: create a directory and use whatever name the provider gave it.
            val created = DocumentsContract.createDocument(resolver, parent.uri, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: throw IOException("mkdir_failed")
            Dir(created, join(parent.actualRel, displayName(created) ?: name))
        }
        dirs[relDir] = dir
        return dir
    }

    private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

    private class Child(val uri: Uri, val isDir: Boolean, val name: String)

    private val childProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    private inline fun forEachChild(parent: Uri, action: (Child) -> Boolean) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
        resolver.query(children, childProjection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val child = Child(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                    c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    name,
                )
                if (!action(child)) return
            }
        }
    }

    /** A child of [parent] called [name] (case-insensitive, as FUSE storage is), preferring a directory, or null. */
    private fun findChild(parent: Uri, name: String): Child? {
        var found: Child? = null
        forEachChild(parent) { c ->
            if (c.name.equals(name, ignoreCase = true)) {
                if (c.isDir) { found = c; false } else { if (found == null) found = c; true }
            } else {
                true
            }
        }
        return found
    }

    /** Lowercase names of all children of [parent] (one listing). */
    private fun listNames(parent: Uri): MutableSet<String> {
        val out = HashSet<String>()
        forEachChild(parent) { out += it.name.lowercase(); true }
        return out
    }

    private fun displayName(doc: Uri): String? = try {
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }

    private companion object {
        const val MAX_ATTEMPTS = 99
    }
}
