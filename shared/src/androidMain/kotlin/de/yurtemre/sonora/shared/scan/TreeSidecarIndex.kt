package de.yurtemre.sonora.shared.scan

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import de.yurtemre.sonora.shared.platform.TreeUriPaths

/** One file found under a SAF tree: filesystem-style [path] plus its document [uri]. */
data class TreeFile(val path: String, val uri: Uri, val lastModifiedMs: Long, val size: Long)

/**
 * Fast SAF tree walk via `DocumentsContract` child queries (one query per directory, far cheaper than
 * `DocumentFile.listFiles()` + per-file lookups). Paths are derived from document ids (LIB-01).
 */
internal object TreeWalker {
    private val PROJ = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    fun walk(resolver: ContentResolver, treeUri: Uri, onFile: (TreeFile) -> Unit) {
        val rootDocId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (e: Exception) { return }
        val stack = ArrayDeque<String>().apply { add(rootDocId) }
        while (stack.isNotEmpty()) {
            val parent = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
            try {
                resolver.query(children, PROJ, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        if (c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) { stack.add(docId); continue }
                        val path = TreeUriPaths.docIdToPath(docId) ?: continue
                        onFile(TreeFile(path, DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            if (c.isNull(2)) 0L else c.getLong(2), if (c.isNull(3)) 0L else c.getLong(3)))
                    }
                }
            } catch (e: Exception) { /* partial listing, like the original */ }
        }
    }
}

/**
 * API 30+: `.lrc`/`.txt` sidecars and image files are invisible to java.io.File, so index them once
 * from the persisted onboarding tree URI. Lookups are by exact (case-sensitive) path.
 */
class TreeSidecarIndex private constructor(
    private val lyrics: Map<String, Uri>,
    private val images: Map<String, Uri>,
) {
    /** LIB-24 order: `<path-no-ext>.lrc` else `.txt`. */
    fun lyricsUriFor(audioPath: String): Uri? =
        de.yurtemre.sonora.shared.lyrics.LyricsParser.sidecarPaths(audioPath).firstNotNullOfOrNull { lyrics[it] }

    /** path → content Uri for every jpg/jpeg/png/webp in the tree. */
    fun artistImageCandidates(): Map<String, Uri> = images

    fun imageUriFor(path: String): Uri? = images[path]

    companion object {
        val EMPTY = TreeSidecarIndex(emptyMap(), emptyMap())
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")

        /** Blocking; call on IO. Never throws (returns [EMPTY] on failure). */
        fun build(resolver: ContentResolver, treeUri: Uri?): TreeSidecarIndex {
            if (treeUri == null) return EMPTY
            val lyrics = HashMap<String, Uri>()
            val images = HashMap<String, Uri>()
            try {
                TreeWalker.walk(resolver, treeUri) { f ->
                    when (f.path.substringAfterLast('.', "").lowercase()) {
                        "lrc", "txt" -> lyrics[f.path] = f.uri
                        in IMAGE_EXTS -> images[f.path] = f.uri
                    }
                }
            } catch (e: Exception) { return EMPTY }
            return TreeSidecarIndex(lyrics, images)
        }

        fun build(resolver: ContentResolver, treeUri: String?): TreeSidecarIndex =
            build(resolver, treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() })
    }
}
