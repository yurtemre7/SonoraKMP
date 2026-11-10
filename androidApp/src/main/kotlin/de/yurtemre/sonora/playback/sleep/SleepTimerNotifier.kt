package de.yurtemre.sonora.playback.sleep

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.yurtemre.sonora.R

/** Ongoing countdown notification (PLAY-27): channel `sleep_timer`, id 8888, chronometer + "+1 min"/"End timer". */
class SleepTimerNotifier(
    private val context: Context,
    /** Service that handles [ACTION_EXTEND] / [ACTION_END] via onStartCommand. */
    private val serviceComponent: ComponentName,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun show(remainingSec: Long, finishingSong: Boolean) {
        if (!canPost()) return
        ensureChannel()
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.sleep_timer_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, context.getString(R.string.sleep_timer_action_extend), servicePi(ACTION_EXTEND, 1))
            .addAction(0, context.getString(R.string.sleep_timer_action_end), servicePi(ACTION_END, 2))
        if (finishingSong) {
            b.setContentText(context.getString(R.string.sleep_timer_notification_finishing))
        } else {
            b.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + remainingSec * 1000)
                .setShowWhen(true)
        }
        try {
            manager.notify(NOTIFICATION_ID, b.build())
        } catch (_: SecurityException) {
        }
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sleep_timer_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.sleep_timer_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun servicePi(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context, requestCode,
            Intent(action).setComponent(serviceComponent),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CHANNEL_ID = "sleep_timer"
        const val NOTIFICATION_ID = 8888
        const val ACTION_EXTEND = "de.yurtemre.sonora.action.SLEEP_TIMER_EXTEND"
        const val ACTION_END = "de.yurtemre.sonora.action.SLEEP_TIMER_END"
    }
}
