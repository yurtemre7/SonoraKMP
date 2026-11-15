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
    private val dirs = HashMap<String, Uri>().apply { put("", rootDocUri) }
    private val open = HashMap<Int, Part>()
    private val done = LinkedHashMap<Int, String>()
    private var cleaned = false

    private class Part(val relPath: String, val parentUri: Uri, val partUri: Uri)

    /** Snapshot of file id -> actual final relPath of every committed file. */
    val committed: Map<Int, String> @Synchronized get() = LinkedHashMap(done)

    /** Deletes every `*.sonora-part` under the tree (left over from an interrupted run). Runs automatically before the first [begin]. */
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
        val parent = ensureDir(SyncPaths.parent(rel))
        val partUri = DocumentsContract.createDocument(
            resolver, parent, "application/octet-stream", SyncNames.partName(SyncPaths.baseName(rel)),
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

    @Synchronized override fun commit(file: ManifestFile, sha256Hex: String) {
        val part = open[file.id] ?: throw IOException("not_begun")
        val intended = (1..MAX_ATTEMPTS).firstNotNullOfOrNull { attempt ->
            val cand = SyncNames.candidate(part.relPath, attempt) ?: return@firstNotNullOfOrNull null
            cand.takeIf { findChild(part.parentUri, SyncPaths.baseName(it)) == null }
        } ?: throw IOException("no_free_name")
        val intendedName = SyncPaths.baseName(intended)
        val renamed = DocumentsContract.renameDocument(resolver, part.partUri, intendedName) ?: throw IOException("rename_failed")
        // A provider may suffix the name on its own; accept that but record what the file is really called.
        val actualName = displayName(renamed) ?: intendedName
        val parentRel = SyncPaths.parent(part.relPath)
        done[file.id] = if (parentRel.isEmpty()) actualName else "$parentRel/$actualName"
        open.remove(file.id)
    }

    @Synchronized override fun abort(file: ManifestFile) {
        val part = open.remove(file.id) ?: return
        runCatching { DocumentsContract.deleteDocument(resolver, part.partUri) }
    }

    /** Document Uri of the directory [relDir] below the root, creating missing levels (cached per relDir). */
    private fun ensureDir(relDir: String): Uri {
        dirs[relDir]?.let { return it }
        val parentUri = ensureDir(SyncPaths.parent(relDir))
        val name = SyncPaths.baseName(relDir)
        val existing = findChild(parentUri, name)
        val uri = if (existing != null && existing.isDir) {
            existing.uri
        } else {
            DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: throw IOException("mkdir_failed")
        }
        dirs[relDir] = uri
        return uri
    }

    private class Child(val uri: Uri, val isDir: Boolean)

    /** The child of [parent] called [name] (case-insensitive, as FUSE storage is), or null. */
    private fun findChild(parent: Uri, name: String): Child? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        resolver.query(children, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1).equals(name, ignoreCase = true)) {
                    return Child(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
            }
        }
        return null
    }

    private fun displayName(doc: Uri): String? = try {
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }

    private companion object {
        const val MAX_ATTEMPTS = 99
    }
}
