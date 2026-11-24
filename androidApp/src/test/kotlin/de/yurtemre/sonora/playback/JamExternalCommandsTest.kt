package de.yurtemre.sonora.playback

import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class JamExternalCommandsTest {
    private val base = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS

    @Test fun outsideAMirrorOutsideControllersKeepEverything() {
        assertEquals(base, externalPlayerCommands(base, mirroring = false))
    }

    @Test fun whileMirroringOutsideControllersCannotSeekOrChangeTheQueue() {
        val c = externalPlayerCommands(base, mirroring = true)
        listOf(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
        ).forEach { assertFalse(c.contains(it), "command $it") }
        // Play/pause and next/previous stay: next/previous become requests to the host (or are ignored).
        assertTrue(c.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(c.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(c.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test fun outsidePlayPauseBecomesARequestWhileMirroring() {
        // The command itself succeeds (no error flashes on the controller); the player's play()/pause() then go to
        // the host as a play or a pause, never as a toggle.
        assertEquals(ExternalCommand.Allow, jamExternalCommand(Player.COMMAND_PLAY_PAUSE, mirroring = true, external = true))
        assertEquals(ExternalTransport.HostPlay, jamExternalTransport(play = true, mirroring = true, external = true))
        assertEquals(ExternalTransport.HostPause, jamExternalTransport(play = false, mirroring = true, external = true))
        assertEquals(ExternalTransport.Local, jamExternalTransport(play = true, mirroring = true, external = false))
        assertEquals(ExternalTransport.Local, jamExternalTransport(play = false, mirroring = false, external = true))
        // The app's own controller (the Jam mirror itself) and any controller outside a Jam act locally.
        assertEquals(ExternalCommand.Allow, jamExternalCommand(Player.COMMAND_PLAY_PAUSE, mirroring = true, external = false))
        assertEquals(ExternalCommand.Allow, jamExternalCommand(Player.COMMAND_PLAY_PAUSE, mirroring = false, external = true))
    }

    @Test fun outsideSeeksAreRejectedAndSkipsPassToTheNavigator() {
        assertEquals(ExternalCommand.Reject, jamExternalCommand(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, mirroring = true, external = true))
        assertEquals(ExternalCommand.Reject, jamExternalCommand(Player.COMMAND_CHANGE_MEDIA_ITEMS, mirroring = true, external = true))
        // next/previous reach the navigator, which turns them into requests (or ignores them).
        assertEquals(ExternalCommand.Allow, jamExternalCommand(Player.COMMAND_SEEK_TO_NEXT, mirroring = true, external = true))
        assertEquals(ExternalCommand.Allow, jamExternalCommand(Player.COMMAND_SET_VOLUME, mirroring = true, external = true))
    }

    @Test fun onlyTheAppsOwnNonNotificationControllerIsInternal() {
        assertFalse(isExternalController("de.yurtemre.sonora", "de.yurtemre.sonora", isNotificationController = false))
        assertTrue(isExternalController("de.yurtemre.sonora", "de.yurtemre.sonora", isNotificationController = true))
        assertTrue(isExternalController("com.android.systemui", "de.yurtemre.sonora", isNotificationController = false))
    }

    @Test fun onlyAFocusLossPauseCountsAsASystemPause() {
        assertTrue(isSystemPause(playWhenReady = false, reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS))
        assertTrue(isSystemPause(playWhenReady = false, reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY))
        assertFalse(isSystemPause(playWhenReady = false, reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
        assertFalse(isSystemPause(playWhenReady = true, reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS))
    }
}
