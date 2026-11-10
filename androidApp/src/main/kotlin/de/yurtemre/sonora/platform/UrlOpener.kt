package de.yurtemre.sonora.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens a URL with a plain ACTION_VIEW (no custom tabs). Returns false on failure. */
object UrlOpener {
    fun open(context: Context, url: String): Boolean {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
        if (uri.scheme.isNullOrBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent); true
        } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }
}
