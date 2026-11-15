package de.yurtemre.sonora.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import de.yurtemre.sonora.shared.platform.TreeUriPaths

/** LIB-01 / D-55: a folder picked via OpenDocumentTree, with its persisted permission and filesystem path. */
data class PickedFolder(val path: String, val uri: String)

object FolderPicker {
    /** Persists read access to [uri] and resolves its path; null when the tree is not on external storage. */
    fun persist(context: Context, uri: Uri): PickedFolder? {
        val path = TreeUriPaths.treeUriToPath(uri) ?: return null
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return PickedFolder(path, uri.toString())
    }

    /** DSYNC-10: like [persist] but takes read and write flags (the receiver writes into the tree). */
    fun persistWritable(context: Context, uri: Uri): PickedFolder? {
        val path = TreeUriPaths.treeUriToPath(uri) ?: return null
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
            return null // no write grant: the caller must not proceed as if it had one
        }
        return PickedFolder(path, uri.toString())
    }
}
