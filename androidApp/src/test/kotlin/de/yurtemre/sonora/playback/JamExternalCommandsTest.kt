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

    @Test fun onlyTheAppsOwnNonNotificationControllerIsInternal() {
        assertFalse(isExternalController("de.yurtemre.sonora", "de.yurtemre.sonora", isNotificationController = false))
        assertTrue(isExternalController("de.yurtemre.sonora", "de.yurtemre.sonora", isNotificationController = true))
        assertTrue(isExternalController("com.android.systemui", "de.yurtemre.sonora", isNotificationController = false))
    }
}
