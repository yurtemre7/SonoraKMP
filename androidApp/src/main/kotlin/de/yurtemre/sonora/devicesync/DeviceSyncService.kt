package de.yurtemre.sonora.devicesync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.devicesync.ServerState
import de.yurtemre.sonora.ui.format.withAppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * DSYNC-13: keeps a device sync running in the background. Foreground type `dataSync`, progress notification
 * (files, bytes, speed, ETA) with Cancel; a high-performance Wi-Fi lock while sending or transferring and a partial
 * wake lock until the received data is applied. Follows [DeviceSyncController.state] and stops itself in every
 * other state (Idle, Finished, Failed, ...).
 */
class DeviceSyncService : Service() {
    private val controller: DeviceSyncController by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyMs = 0L
    private var lastKind: Class<*>? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) controller.cancel()
        val state = controller.state.value
        // Always enter the foreground first: after startForegroundService, stopping without it crashes the app.
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, build(state),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (_: Exception) {
            // Not allowed from the background (API 31+): the work goes on while the process lives.
        }
        // The observer gets the current state at once and stops the service if nothing needs it.
        if (observer == null) {
            observer = scope.launch { controller.state.collect(::onState) }
        } else if (!isActive(controller.state.value)) {
            stop()
        }
        return START_NOT_STICKY
    }

    /** API 35+: the daily dataSync time limit is used up; stop as the system requires. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        controller.cancel()
        stop()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun onState(state: DeviceSyncState) {
        if (!isActive(state)) {
            stop()
            return
        }
        // The high-performance Wi-Fi lock only while bytes move, not while the sender just waits for a receiver.
        val serving = (state as? DeviceSyncState.Sending)?.server.let { it is ServerState.Paired || it is ServerState.Sending }
        holdLocks(wifi = serving || state is DeviceSyncState.Transferring)
        // Progress arrives ~10x a second; the system drops notification updates beyond a few per second.
        val now = SystemClock.elapsedRealtime()
        if (state.javaClass != lastKind || now - lastNotifyMs >= NOTIFY_INTERVAL_MS) {
            lastKind = state.javaClass
            lastNotifyMs = now
            notify(build(state))
        }
    }

    private fun stop() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Only if no newer start is queued: that one still has to call startForeground.
        stopSelfResult(lastStartId)
    }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF: still the documented high-throughput mode for LAN transfers
    private fun holdLocks(wifi: Boolean) {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(MAX_LOCK_MS)
            }
        }
        if (wifi && wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!wifi) {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun build(state: DeviceSyncState): Notification {
        ensureChannel()
        val ctx = withAppLocale()
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, ctx.getString(R.string.devicesync_notif_cancel), cancelIntent())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            b.setContentIntent(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        when (state) {
            is DeviceSyncState.Sending -> {
                b.setContentTitle(ctx.getString(R.string.devicesync_notif_sending_title))
                when (val s = state.server) {
                    is ServerState.Paired -> b.setContentText(ctx.getString(R.string.devicesync_notif_connected, s.peerName))
                    is ServerState.Sending -> {
                        b.setContentText(ctx.getString(R.string.devicesync_notif_sent_bytes, size(s.bytesSent), size(s.totalBytes)))
                        b.setProgress(PROGRESS_MAX, fraction(s.bytesSent, s.totalBytes), false)
                    }
                    ServerState.Done -> b.setContentText(ctx.getString(R.string.devicesync_notif_sender_done))
                    else -> b.setContentText(ctx.getString(R.string.devicesync_notif_waiting))
                }
            }
            is DeviceSyncState.Transferring -> {
                val p = state.progress
                b.setContentTitle(ctx.getString(R.string.devicesync_notif_receiving_title))
                b.setContentText(
                    ctx.resources.getQuantityString(
                        R.plurals.devicesync_notif_progress, p.filesTotal,
                        p.filesDone, p.filesTotal, size(p.totalBytes), size(p.totalSize),
                    ),
                )
                if (p.bytesPerSecond > 0) {
                    val etaSec = (p.totalSize - p.totalBytes).coerceAtLeast(0) / p.bytesPerSecond
                    b.setSubText(ctx.getString(R.string.devicesync_notif_speed_eta, size(p.bytesPerSecond), DateUtils.formatElapsedTime(etaSec)))
                }
                b.setProgress(PROGRESS_MAX, fraction(p.totalBytes, p.totalSize), false)
            }
            else -> {
                b.setContentTitle(ctx.getString(R.string.devicesync_notif_receiving_title))
                b.setContentText(ctx.getString(R.string.devicesync_notif_applying))
                b.setProgress(0, 0, true)
            }
        }
        return b.build()
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)

    private fun fraction(done: Long, total: Long) =
        if (total <= 0) 0 else (done.coerceIn(0, total) * PROGRESS_MAX / total).toInt()

    private fun notify(notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, DeviceSyncService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ctx = withAppLocale()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ctx.getString(R.string.devicesync_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.devicesync_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "device_sync"
        const val NOTIFICATION_ID = 8890
        const val ACTION_CANCEL = "de.yurtemre.sonora.action.DEVICE_SYNC_CANCEL"
        private const val WAKE_LOCK_TAG = "sonora:device_sync"
        private const val NOTIFY_INTERVAL_MS = 1_000L
        private const val PROGRESS_MAX = 1_000
        /** Safety net against a leaked lock; matches the dataSync foreground limit. */
        private const val MAX_LOCK_MS = 6 * 60 * 60_000L

        /** States that need the service; any other state stops it. */
        fun isActive(state: DeviceSyncState) = state is DeviceSyncState.Sending ||
            state is DeviceSyncState.Transferring || state is DeviceSyncState.Applying

        /** Starts (or pokes) the service; called by the controller when sending or a transfer starts. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DeviceSyncService::class.java))
            } catch (_: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (app in background): carry on without the service.
            }
        }
    }
}
