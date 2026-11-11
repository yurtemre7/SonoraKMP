package de.yurtemre.sonora.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import java.io.File

/** Installs an APK through a [PackageInstaller] session (UPD-08) and suspends until the final status arrives. */
class PackageInstallerHelper(private val context: Context) {

    suspend fun install(apk: File): InstallResult = coroutineScope {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = withContext(Dispatchers.IO) { installer.createSession(params) }
        // Subscribe before committing so the result can't be missed.
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            InstallResultBus.results.first { it.first == sessionId }.second
        }
        try {
            withContext(Dispatchers.IO) {
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                        session.fsync(out)
                    }
                    val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                    val pi = PendingIntent.getBroadcast(
                        context, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pi.intentSender)
                }
            }
        } catch (t: Throwable) {
            result.cancel()
            runCatching { installer.abandonSession(sessionId) }
            if (t is kotlinx.coroutines.CancellationException) throw t
            return@coroutineScope InstallResult.Other(PackageInstaller.STATUS_FAILURE, t.message)
        }
        result.await()
    }
}
