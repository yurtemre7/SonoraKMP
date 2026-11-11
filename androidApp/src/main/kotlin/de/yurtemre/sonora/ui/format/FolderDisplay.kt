package de.yurtemre.sonora.ui.format

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import java.io.File

/**
 * Friendly display of a scan-folder path: `/storage/emulated/0/Music/Test` → `Music › Test`;
 * on removable storage the volume label leads (`SD card › Music`).
 */
object FolderDisplay {
    private const val SEP = " › "
    private val PRIMARY = Regex("""^/storage/emulated/\d+/?""")
    private val REMOVABLE = Regex("""^/storage/([^/]+)/?""")

    fun format(path: String, volumeLabel: (String) -> String? = { null }): String {
        val clean = path.trimEnd('/')
        PRIMARY.find(clean)?.let { m ->
            val rest = clean.substring(m.range.last + 1).split('/').filter { it.isNotEmpty() }
            return if (rest.isEmpty()) clean else rest.joinToString(SEP)
        }
        REMOVABLE.find(clean)?.let { m ->
            val uuid = m.groupValues[1]
            if (uuid != "emulated" && uuid != "self") {
                val rest = clean.substring(m.range.last + 1).split('/').filter { it.isNotEmpty() }
                val label = volumeLabel(uuid) ?: uuid
                return (listOf(label) + rest).joinToString(SEP)
            }
        }
        return clean
    }

    fun format(context: Context, path: String): String = format(path) { uuid -> label(context, uuid) }

    private fun label(context: Context, uuid: String): String? = runCatching {
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= 30) {
            sm.storageVolumes.firstOrNull { it.directory?.name == uuid || it.uuid == uuid }?.getDescription(context)
        } else {
            sm.getStorageVolume(File("/storage/$uuid"))?.getDescription(context)
        }
    }.getOrNull()
}
