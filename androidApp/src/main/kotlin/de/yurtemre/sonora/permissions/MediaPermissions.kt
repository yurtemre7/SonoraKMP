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

/** Runtime permissions: audio (LIB-02, required), notifications (33+, best-effort), local network (37+, device sync). */
object MediaPermissions {
    val audio: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Storage request: audio only. Artist images are read through the SAF folder grant (D-55). */
    val storageRequest: Array<String> = arrayOf(audio)

    val notifications: String? = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

    /**
     * Device sync (DSYNC-02/04): from Android 17 an app targeting API 37 needs this runtime grant to discover
     * (NSD) and connect to devices on the local network; null below 37, where it isn't needed.
     */
    val localNetwork: String? = if (Build.VERSION.SDK_INT >= 37) Manifest.permission.ACCESS_LOCAL_NETWORK else null

    fun hasLocalNetwork(context: Context): Boolean = localNetwork?.let { granted(context, it) } ?: true

    /** All files access (API 30+): a special grant given on a system settings screen, not a runtime dialog. Optional. */
    /** All files access only exists from API 30; below that the onboarding row and button are hidden. */
    val allFilesSupported: Boolean = Build.VERSION.SDK_INT >= 30

    fun hasAllFiles(): Boolean =
        allFilesSupported && try {
            android.os.Environment.isExternalStorageManager()
        } catch (_: RuntimeException) {
            false // optional grant: an unreadable state counts as not granted
        }

    /** Opens the per-app "All files access" screen, falling back to the app details page. */
    fun openAllFilesSettings(context: Context) {
        if (!allFilesSupported) return openAppSettings(context)
        val uri = Uri.fromParts("package", context.packageName, null)
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            openAppSettings(context)
        }
    }

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
