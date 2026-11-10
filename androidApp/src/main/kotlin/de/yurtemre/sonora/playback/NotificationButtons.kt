package de.yurtemre.sonora.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import de.yurtemre.sonora.R

/** D-08 media button preferences: Close when no timer; "+1 min" and "End timer" while a sleep timer runs. */
@OptIn(UnstableApi::class)
object NotificationButtons {
    fun build(context: Context, timerActive: Boolean): List<CommandButton> =
        if (!timerActive) {
            listOf(button(CommandButton.ICON_STOP, context.getString(R.string.notification_close_player), SessionCommands.CLOSE_PLAYER))
        } else {
            listOf(
                button(CommandButton.ICON_PLUS, context.getString(R.string.sleep_timer_action_extend), SessionCommands.SLEEP_EXTEND),
                button(CommandButton.ICON_BLOCK, context.getString(R.string.sleep_timer_action_end), SessionCommands.SLEEP_STOP),
            )
        }

    private fun button(icon: Int, label: String, action: String) = CommandButton.Builder(icon)
        .setDisplayName(label)
        .setSessionCommand(SessionCommands.command(action))
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build()
}
