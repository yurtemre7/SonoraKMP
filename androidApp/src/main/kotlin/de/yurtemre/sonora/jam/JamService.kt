package de.yurtemre.sonora.jam

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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.format.withAppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Keeps a Jam running with the screen off (the [de.yurtemre.sonora.devicesync.DeviceSyncService] pattern): a
 * foreground service with a low-latency Wi-Fi lock and a partial wake lock while [JamController.state] is hosting,
 * joining or joined; it stops itself in every other state. Type `mediaPlayback`, not `dataSync` like device sync:
 * a Jam is synchronized playback and may run for hours, and `dataSync` has a daily time limit (API 35+).
 *
 * As a guest it watches Wi-Fi: when the network is lost or changes, the connection ends cleanly
 * ([JamController.onNetworkLost]) and the Jam screen offers a rejoin.
 */
class JamService : Service() {
    private val controller: JamController by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastKind: Class<*>? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_LEAVE) controller.leave()
        val state = controller.state.value
        // Always enter the foreground first: after startForegroundService, stopping without it crashes the app.
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, build(state),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (_: Exception) {
            // Not allowed from the background (API 31+): the Jam goes on while the process lives.
        }
        if (observer == null) {
            observer = scope.launch { controller.state.collect(::onState) }
        } else if (!isActive(controller.state.value)) {
            stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        unwatchNetwork()
        releaseLocks()
        super.onDestroy()
    }

    private fun onState(state: JamUiState) {
        if (!isActive(state)) {
            stop()
            return
        }
        holdLocks()
        if (state is JamUiState.Joined) watchNetwork() else unwatchNetwork()
        // Hosting changes with every guest; the guest state with every frame: only redraw what the text shows.
        if (state.javaClass != lastKind || state is JamUiState.Hosting) {
            lastKind = state.javaClass
            notify(build(state))
        }
    }

    private fun stop() {
        unwatchNetwork()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Only if no newer start is queued: that one still has to call startForeground.
        stopSelfResult(lastStartId)
    }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF: the low-latency mode needs API 29
    private fun holdLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(MAX_LOCK_MS)
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** A guest's Wi-Fi network going away (or switching) ends its connection cleanly. */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                scope.launch { controller.onNetworkLost() }
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: RuntimeException) {
            // Too many callbacks or no permission: a drop is still noticed by the connection itself.
        }
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private fun build(state: JamUiState): Notification {
        ensureChannel()
        val ctx = withAppLocale()
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            b.setContentIntent(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        when (state) {
            is JamUiState.Hosting -> {
                b.setContentTitle(ctx.getString(R.string.jam_notif_hosting_title))
                b.setContentText(
                    ctx.resources.getQuantityString(R.plurals.jam_notif_hosting_text, state.guests.size, state.code, state.guests.size),
                )
                b.addAction(0, ctx.getString(R.string.jam_notif_end), leaveIntent())
            }
            is JamUiState.Joined -> {
                b.setContentTitle(ctx.getString(R.string.jam_notif_joined_title))
                b.setContentText(ctx.getString(R.string.jam_notif_joined_text, state.hostName))
                b.addAction(0, ctx.getString(R.string.jam_notif_leave), leaveIntent())
            }
            else -> b.setContentTitle(ctx.getString(R.string.jam_notif_joining))
        }
        return b.build()
    }

    private fun notify(notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun leaveIntent(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, JamService::class.java).setAction(ACTION_LEAVE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ctx = withAppLocale()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ctx.getString(R.string.jam_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.jam_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "jam"
        const val NOTIFICATION_ID = 8891
        const val ACTION_LEAVE = "de.yurtemre.sonora.action.JAM_LEAVE"
        private const val WAKE_LOCK_TAG = "sonora:jam"
        /** Safety net against a leaked lock. */
        private const val MAX_LOCK_MS = 12 * 60 * 60_000L

        /** States that need the service; any other state stops it. */
        fun isActive(state: JamUiState) =
            state is JamUiState.Hosting || state is JamUiState.Joining || state is JamUiState.Joined

        /** Starts (or pokes) the service; called by the controller when a Jam starts. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, JamService::class.java))
            } catch (_: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (app in background): carry on without the service.
            }
        }
    }
}
