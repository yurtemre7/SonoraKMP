package de.yurtemre.sonora.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** LIB-02 runtime permissions: audio (required), images (best-effort), notifications (33+, best-effort). */
object MediaPermissions {
    val audio: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Requested together with [audio]; images are best-effort and never block. */
    val storageRequest: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(audio, Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(audio)

    val notifications: String? = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

    fun hasAudio(context: Context): Boolean = granted(context, audio)

    /** Below API 33 notifications need no runtime grant. */
    fun hasNotifications(context: Context): Boolean = notifications?.let { granted(context, it) } ?: true

    /** True when the system will no longer show the dialog (denied with "don't ask again"). */
    fun isPermanentlyDenied(activity: Activity, permission: String): Boolean =
        !granted(activity, permission) && !activity.shouldShowRequestPermissionRationale(permission)

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
