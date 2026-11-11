package de.yurtemre.sonora.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/** "Install unknown apps" permission (UPD-08): check, and open the per-app settings page. Re-check on resume. */
class InstallPermissionHelper(private val context: Context) {
    /** Before API 26 "unknown sources" is a global setting the installer prompt handles itself. */
    fun canRequestPackageInstalls(): Boolean =
        android.os.Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openSettings() {
        if (android.os.Build.VERSION.SDK_INT < 26) {
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
