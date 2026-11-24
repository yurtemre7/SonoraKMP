package de.yurtemre.sonora.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Player commands outside controllers (notification and lock-screen seek bar, Bluetooth/headset, Android Auto)
 * lose while a Jam guest mirrors the host: seeking and changing the queue would make the guest drift. Play/pause
 * stay (a local pause is undone by the host's next state), and next/previous stay because the service turns them
 * into requests to the host (or ignores them when the host does not allow guests to skip).
 */
private val JAM_GUEST_BLOCKED = intArrayOf(
    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
    Player.COMMAND_SEEK_BACK,
    Player.COMMAND_SEEK_FORWARD,
    Player.COMMAND_SET_MEDIA_ITEM,
    Player.COMMAND_CHANGE_MEDIA_ITEMS,
    Player.COMMAND_SET_SHUFFLE_MODE,
    Player.COMMAND_SET_REPEAT_MODE,
)

/** [base] for an outside controller, minus the Jam-guest-blocked commands while [mirroring]. */
@OptIn(UnstableApi::class)
fun externalPlayerCommands(base: Player.Commands, mirroring: Boolean): Player.Commands =
    if (!mirroring) base else base.buildUpon().removeAll(*JAM_GUEST_BLOCKED).build()

/**
 * Every controller except the app's own UI controller: other packages (system UI, Bluetooth, Auto) and the media
 * notification's controller, which is in-process but serves the notification buttons.
 */
fun isExternalController(controllerPackage: String, ownPackage: String, isNotificationController: Boolean): Boolean =
    controllerPackage != ownPackage || isNotificationController

/** What the session does with a player command from a controller while a Jam guest may be mirroring. */
enum class ExternalCommand { Allow, RequestPlayPause, Reject }

/**
 * An outside controller's (notification, lock screen, headset, Bluetooth, Auto) play/pause while mirroring becomes
 * a request to the host (sent only when the host allows it) instead of a local pause the host would undo; its
 * seeks and queue changes are rejected. Next/previous pass on to the navigator, which maps them the same way.
 * The app's own controller (the Jam mirror) and everything outside a Jam act as usual.
 */
fun jamExternalCommand(command: Int, mirroring: Boolean, external: Boolean): ExternalCommand = when {
    !mirroring || !external -> ExternalCommand.Allow
    command == Player.COMMAND_PLAY_PAUSE -> ExternalCommand.RequestPlayPause
    command in JAM_GUEST_BLOCKED -> ExternalCommand.Reject
    else -> ExternalCommand.Allow
}
