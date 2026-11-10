package de.yurtemre.sonora.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import de.yurtemre.sonora.MainActivity
import de.yurtemre.sonora.playback.SonoraPlaybackService

/**
 * Intent actions the widget sends to [SonoraPlaybackService] (WID-05). The
 * service maps them to player commands in `onStartCommand` (P4-11); a cold
 * start should resume the last queue via `onPlaybackResumption`.
 */
object WidgetActions {
    const val ACTION_PLAY_PAUSE = "de.yurtemre.sonora.widget.PLAY_PAUSE"
    const val ACTION_NEXT = "de.yurtemre.sonora.widget.NEXT"
    const val ACTION_PREVIOUS = "de.yurtemre.sonora.widget.PREVIOUS"

    val ALL = setOf(ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREVIOUS)

    internal fun service(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, SonoraPlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags)
        }
    }

    internal fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
