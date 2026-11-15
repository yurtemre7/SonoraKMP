package de.yurtemre.sonora.devicesync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import de.yurtemre.sonora.shared.platform.TreeUriPaths

/** DSYNC-10: where received files go, and how much room is left there. */
class DestinationFolder(private val context: Context) {
    /** [scanFolderUri] when a persisted permission grants write access to exactly that tree, else null (ask the user). */
    fun defaultDestination(scanFolderUri: String?): Uri? {
        val uri = scanFolderUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return uri.takeIf { granted }
    }

    /** `EXTRA_INITIAL_URI` for the folder picker: the scan folder, else the `Music/` folder of primary storage. */
    fun initialUri(scanFolderUri: String?): Uri =
        scanFolderUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Music")

    /**
     * Free bytes under [treeUri]: `StatFs` on the tree's filesystem path, else (API 26+)
     * `StorageManager.getAllocatableBytes` of the default volume. The fallback applies only to primary storage. Null when neither is available (API 24/25
     * without a resolvable path, or an unresolvable non-primary volume) - callers treat that as "unknown" and skip the space check.
     */
    fun freeBytes(treeUri: Uri): Long? {
        TreeUriPaths.treeUriToPath(treeUri)?.let { path ->
            try { return StatFs(path).availableBytes } catch (_: Exception) { }
        }
        // The fallback reports internal storage: only valid when the tree is on primary storage.
        if (!isPrimary(treeUri)) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                return sm.getAllocatableBytes(StorageManager.UUID_DEFAULT)
            } catch (_: Exception) { }
        }
        return null
    }

    private fun isPrimary(treeUri: Uri): Boolean = try {
        DocumentsContract.getTreeDocumentId(treeUri).startsWith("primary:", ignoreCase = true)
    } catch (_: Exception) { false }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
