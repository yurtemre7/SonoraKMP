package de.yurtemre.sonora.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File

/** Opens the folder containing a file (reduced strategy list, SPEC D-31). */
object FolderOpener {
    enum class Result { OPENED_SAMSUNG, OPENED_FILE_MANAGER, OPENED_DOCUMENTS_UI, OPENED_GENERIC, FAILED }

    private const val SAMSUNG_PKG = "com.sec.android.app.myfiles"
    private const val EXT_AUTHORITY = "com.android.externalstorage.documents"

    fun open(context: Context, filePath: String): Result {
        val target = File(filePath)
        val folder = if (target.isDirectory) target else (target.parentFile ?: target)

        // 1. Samsung My Files (One UI)
        if (tryStart(context, Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES").apply {
                setPackage(SAMSUNG_PKG)
                putExtra("samsung.myfiles.intent.extra.START_PATH", folder.absolutePath)
            })) return Result.OPENED_SAMSUNG

        // 2. Generic VIEW resource/folder (third-party file managers, excluding DocumentsUI)
        val folderUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", folder)
        }.getOrElse { Uri.fromFile(folder) }
        for (mime in listOf("resource/folder", "vnd.android.document/directory")) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val handler = runCatching { context.packageManager.queryIntentActivities(intent, 0) }
                .getOrDefault(emptyList())
                .firstOrNull { !it.activityInfo.packageName.lowercase().contains("documentsui") }
            if (handler != null) {
                intent.setClassName(handler.activityInfo.packageName, handler.activityInfo.name)
                if (tryStart(context, intent)) return Result.OPENED_FILE_MANAGER
            }
        }

        // 3. DocumentsUI via ACTION_OPEN_DOCUMENT_TREE at the folder
        if (tryStart(context, Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildTreeDocumentUri(EXT_AUTHORITY, documentId(folder)))
            })) return Result.OPENED_DOCUMENTS_UI

        // 4. Generic VIEW chooser
        val generic = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderUri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (tryStart(context, Intent.createChooser(generic, null))) return Result.OPENED_GENERIC
        return Result.FAILED
    }

    internal fun documentId(folder: File): String {
        val norm = folder.absolutePath.replace('\\', '/')
        for (p in listOf("/storage/emulated/0", "/sdcard")) {
            if (norm.equals(p, true) || norm.startsWith("$p/", true)) {
                return "primary:" + norm.substring(p.length).trimStart('/')
            }
        }
        if (norm.startsWith("/storage/", true)) {
            val parts = norm.removePrefix("/storage/").split('/', limit = 2)
            val vol = parts.firstOrNull().orEmpty()
            if (vol.isNotEmpty() && vol != "emulated") return "$vol:${parts.getOrElse(1) { "" }}"
        }
        return "primary:"
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }
}
