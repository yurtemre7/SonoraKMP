package de.yurtemre.sonora.ui.screens.jam

import de.yurtemre.sonora.R
import de.yurtemre.sonora.jam.JamEndReason
import de.yurtemre.sonora.jam.JamGuestUi
import de.yurtemre.sonora.jam.JamQueueItem
import de.yurtemre.sonora.jam.JamSyncState
import de.yurtemre.sonora.jam.JamUiState
import de.yurtemre.sonora.shared.jam.JamError
import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.shared.jam.JamSong
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JamPresentationTest {

    // ---- code -------------------------------------------------------------------------------------------------------

    @Test fun codeIsGroupedThreeAndThree() {
        assertEquals("123 456", formatJamCode("123456"))
        assertEquals("12345", formatJamCode("12345"))
    }

    @Test fun codeIsSpokenDigitByDigit() {
        assertEquals("1 2 3 4 5 6", spokenJamCode("123456"))
    }

    // ---- manual address ---------------------------------------------------------------------------------------------

    @Test fun ipv4AndPortParse() {
        assertEquals(JamAddress("192.168.1.20", 41234), parseJamAddress(" 192.168.1.20:41234 "))
    }

    @Test fun hostnameAndPortParse() {
        assertEquals(JamAddress("pixel-7.local", 5000), parseJamAddress("pixel-7.local:5000"))
        assertEquals(JamAddress("myphone", 1), parseJamAddress("myphone:1"))
    }

    @Test fun portBoundsAre1To65535() {
        assertEquals(65535, parseJamAddress("10.0.0.2:65535")?.port)
        assertNull(parseJamAddress("10.0.0.2:0"))
        assertNull(parseJamAddress("10.0.0.2:65536"))
        assertNull(parseJamAddress("10.0.0.2:999999"))
        assertNull(parseJamAddress("10.0.0.2:-1"))
        assertNull(parseJamAddress("10.0.0.2:+80"))
    }

    @Test fun badInputIsRejected() {
        listOf(
            "", "   ", "10.0.0.2", ":5000", "10.0.0.2:", "10.0.0.2:abc", "10.0.0.256:5000", "10.0.0:5000",
            "1.2.3.4.5:5000", "0.0.0.0:5000", "255.255.255.255:5000", "fe80::1:5000", "-host:5000", "host-:5000",
            "ho st:5000", "host..local:5000", "http://10.0.0.2:5000", "999.1.1.1:5000", "a".repeat(64) + ":5000",
        ).forEach { assertNull(parseJamAddress(it), "accepted '$it'") }
    }

    // ---- errors -----------------------------------------------------------------------------------------------------

    @Test fun everyErrorHasItsOwnMessageAndAction() {
        assertEquals(R.string.jam_error_wrong_code, jamErrorMessage(JamError.WrongCode))
        assertEquals(R.string.jam_error_full, jamErrorMessage(JamError.Full))
        assertEquals(R.string.jam_error_version, jamErrorMessage(JamError.VersionMismatch))
        assertEquals(R.string.jam_error_code_exhausted, jamErrorMessage(JamError.CodeExhausted))
        assertEquals(R.string.jam_error_host_gone, jamErrorMessage(JamError.HostGone))
        assertEquals(R.string.jam_error_reconnect_refused, jamErrorMessage(JamError.ReconnectRefused))
        assertEquals(JamErrorAction.TryAgain, jamErrorAction(JamError.WrongCode))
        assertEquals(JamErrorAction.Wait, jamErrorAction(JamError.Locked))
        assertEquals(JamErrorAction.AskForNewCode, jamErrorAction(JamError.CodeExhausted))
        assertEquals(JamErrorAction.UpdateApp, jamErrorAction(JamError.VersionMismatch))
        assertEquals(JamErrorAction.None, jamErrorAction(JamError.Full))
        assertEquals(JamErrorAction.TryAgain, jamErrorAction(JamError.HostGone))
    }

    // ---- speaker delay ----------------------------------------------------------------------------------------------

    @Test fun speakerDelaySnapsToTensAndClamps() {
        assertEquals(0, snapSpeakerDelay(4f))
        assertEquals(10, snapSpeakerDelay(6f))
        assertEquals(-120, snapSpeakerDelay(-118.2f))
        assertEquals(500, snapSpeakerDelay(812f))
        assertEquals(-500, snapSpeakerDelay(-900f))
    }

    @Test fun speakerDelayStepsBy10WithinRange() {
        assertEquals(10, stepSpeakerDelay(0, +1))
        assertEquals(-10, stepSpeakerDelay(0, -1))
        assertEquals(500, stepSpeakerDelay(500, +1))
        assertEquals(-500, stepSpeakerDelay(-500, -1))
        assertEquals(130, stepSpeakerDelay(127, +1))
    }

    @Test fun speakerDelayNumberIsSigned() {
        assertEquals("0", signedMs(0))
        assertEquals("+120", signedMs(120))
        assertEquals("−" + "80", signedMs(-80))
    }

    // ---- now playing flags ------------------------------------------------------------------------------------------

    private val song = JamSong("A", "Artist", "Album", 180_000)

    private fun joined(canAdd: Boolean = true, canControl: Boolean = false, sync: JamSyncState = JamSyncState.InSync) =
        JamUiState.Joined(
            "Host phone", listOf(JamQueueItem(song, true), JamQueueItem(song.copy(title = "B"), false)), 0, true,
            canAdd, canControl, sync,
        )

    @Test fun outsideAJamEverythingIsShown() {
        val ui = jamPlayerUi(JamUiState.Idle)
        assertEquals(JamRole.None, ui.role)
        assertTrue(ui.showSeek && ui.showQueueEditing && ui.showShuffleRepeat && ui.showSleepTimer && ui.showSkip && ui.showPlayPause)
        assertFalse(ui.showAddSong)
    }

    @Test fun guestsNeverSeeSeekReorderShuffleRepeatOrSleepTimer() {
        val ui = jamPlayerUi(joined(canControl = true))
        assertEquals(JamRole.Guest, ui.role)
        assertEquals("Host phone", ui.hostName)
        assertFalse(ui.showSeek)
        assertFalse(ui.showQueueEditing)
        assertFalse(ui.showShuffleRepeat)
        assertFalse(ui.showSleepTimer)
    }

    @Test fun guestsSeeSkipAndPauseOnlyWhenTheHostAllows() {
        assertFalse(jamPlayerUi(joined(canControl = false)).showSkip)
        assertFalse(jamPlayerUi(joined(canControl = false)).showPlayPause)
        assertTrue(jamPlayerUi(joined(canControl = true)).showSkip)
        assertTrue(jamPlayerUi(joined(canControl = true)).showPlayPause)
    }

    @Test fun guestsSeeAddSongOnlyWhenTheHostAllows() {
        assertTrue(jamPlayerUi(joined(canAdd = true)).showAddSong)
        assertFalse(jamPlayerUi(joined(canAdd = false)).showAddSong)
    }

    @Test fun syncStatusIsPlain() {
        assertEquals(JamStatus.Syncing, jamPlayerUi(joined(sync = JamSyncState.Syncing)).status)
        assertEquals(JamStatus.InSync, jamPlayerUi(joined(sync = JamSyncState.InSync)).status)
        assertEquals(JamStatus.Reconnecting, jamPlayerUi(joined(sync = JamSyncState.Reconnecting)).status)
        assertEquals(R.string.jam_status_in_sync, jamStatusText(JamStatus.InSync))
        assertEquals(R.string.jam_status_syncing, jamStatusText(JamStatus.Syncing))
    }

    @Test fun hostDroppingShowsEndedOnlyAfterTheSongEnds() {
        // The host is gone, but the current song still plays: no "Jam ended" yet.
        val ending = jamPlayerUi(joined(sync = JamSyncState.Ending))
        assertEquals(JamRole.Guest, ending.role)
        assertEquals(JamStatus.EndingAfterSong, ending.status)
        assertNull(ending.ended)
        // The controller reports Ended once the song is over.
        val ended = jamPlayerUi(JamUiState.Ended(JamEndReason.HostGone))
        assertEquals(JamRole.None, ended.role)
        assertEquals(JamUiState.Ended(JamEndReason.HostGone), ended.ended)
        assertTrue(ended.showSeek)
    }

    @Test fun hostKeepsAllControls() {
        val ui = jamPlayerUi(JamUiState.Hosting("123456", 4000, listOf(JamGuestUi("g", "Guest")), JamSettings()))
        assertEquals(JamRole.Host, ui.role)
        assertEquals(1, ui.guestCount)
        assertTrue(ui.showSeek && ui.showQueueEditing && ui.showSkip && ui.showPlayPause)
    }

    @Test fun endReasonsHaveMessages() {
        assertEquals(R.string.jam_ended_host_gone, jamEndMessage(JamEndReason.HostGone))
        assertEquals(R.string.jam_ended_host_ended, jamEndMessage(JamEndReason.HostEnded))
        assertEquals(R.string.jam_ended_kicked, jamEndMessage(JamEndReason.Kicked))
        assertEquals(R.string.jam_ended_network, jamEndMessage(JamEndReason.NetworkChanged))
    }
}
