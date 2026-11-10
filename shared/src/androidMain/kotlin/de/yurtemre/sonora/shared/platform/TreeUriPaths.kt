package de.yurtemre.sonora.shared.platform

import android.net.Uri
import android.provider.DocumentsContract

/** LIB-01 tree URI ↔ filesystem path (`primary:Music` → `/storage/emulated/0/Music`, `<uuid>:x` → `/storage/<uuid>/x`). */
object TreeUriPaths {
    private const val AUTHORITY = "com.android.externalstorage.documents"

    fun treeUriToPath(uri: Uri): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        if (uri.authority != AUTHORITY) null else docIdToPath(docId)
    } catch (e: Exception) { null }

    fun treeUriToPath(uri: String): String? = treeUriToPath(Uri.parse(uri))

    fun docIdToPath(docId: String): String? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val rel = docId.substring(colon + 1).trim('/')
        val base = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    fun pathToDocId(path: String): String? {
        val p = path.trimEnd('/').replaceFirst(Regex("^/sdcard(?=/|$)"), "/storage/emulated/0")
        return when {
            p == "/storage/emulated/0" -> "primary:"
            p.startsWith("/storage/emulated/0/") -> "primary:" + p.removePrefix("/storage/emulated/0/")
            p.startsWith("/storage/") -> {
                val rest = p.removePrefix("/storage/")
                val vol = rest.substringBefore('/')
                if (vol.isEmpty() || vol == "emulated" || vol == "self") null
                else "$vol:" + rest.substringAfter('/', "")
            }
            else -> null
        }
    }

    fun pathToTreeUri(path: String): Uri? = pathToDocId(path)?.let { DocumentsContract.buildTreeDocumentUri(AUTHORITY, it) }
}
