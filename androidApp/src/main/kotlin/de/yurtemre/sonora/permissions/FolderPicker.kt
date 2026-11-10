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
}
