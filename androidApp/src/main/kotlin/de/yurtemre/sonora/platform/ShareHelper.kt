package de.yurtemre.sonora.platform

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import de.yurtemre.sonora.R
import java.io.File

/** Shares one or many audio files via a chooser. */
object ShareHelper {
    data class Item(val path: String, val mediaStoreId: Long? = null, val mimeType: String? = null)

    fun share(context: Context, items: List<Item>): Boolean {
        val uris = items.mapNotNull { toUri(context, it) }
        if (uris.isEmpty()) return false
        val mime = items.mapNotNull { it.mimeType }.distinct().singleOrNull() ?: "audio/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }.apply {
            type = mime
            clipData = ClipData.newRawUri(null, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.platform_share_chooser_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    fun shareOne(context: Context, path: String, mediaStoreId: Long? = null): Boolean =
        share(context, listOf(Item(path, mediaStoreId)))

    private fun toUri(context: Context, item: Item): Uri? {
        item.mediaStoreId?.takeIf { it > 0 }?.let {
            return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it)
        }
        val file = File(item.path)
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }
}
