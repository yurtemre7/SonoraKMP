package de.yurtemre.sonora.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Typed PackageInstaller outcome (UPD-08). */
sealed interface InstallResult {
    data object Success : InstallResult
    data object Cancelled : InstallResult
    data object Conflict : InstallResult
    data object Incompatible : InstallResult
    data object Storage : InstallResult
    data class Other(val status: Int, val message: String?) : InstallResult

    companion object {
        fun fromStatus(status: Int, message: String?): InstallResult = when (status) {
            PackageInstaller.STATUS_SUCCESS -> Success
            PackageInstaller.STATUS_FAILURE_ABORTED -> Cancelled
            PackageInstaller.STATUS_FAILURE_CONFLICT -> Conflict
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Incompatible
            PackageInstaller.STATUS_FAILURE_STORAGE -> Storage
            else -> Other(status, message)
        }
    }
}

/** Process-wide bus from [InstallResultReceiver] to [PackageInstallerHelper]. */
object InstallResultBus {
    private val _results = MutableSharedFlow<Pair<Int, InstallResult>>(extraBufferCapacity = 8)
    val results: SharedFlow<Pair<Int, InstallResult>> = _results.asSharedFlow()
    fun post(sessionId: Int, result: InstallResult) { _results.tryEmit(sessionId to result) }
}

/** Receives PackageInstaller session status; launches the system confirmation when user action is pending. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm == null) {
                InstallResultBus.post(sessionId, InstallResult.Other(status, "Missing confirmation intent"))
                return
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirm) }
                .onFailure { InstallResultBus.post(sessionId, InstallResult.Other(status, it.message)) }
            return
        }
        InstallResultBus.post(sessionId, InstallResult.fromStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
    }
}
