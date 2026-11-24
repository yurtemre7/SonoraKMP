package de.yurtemre.sonora.playback

import de.yurtemre.sonora.shared.model.QueueEntry
import de.yurtemre.sonora.shared.model.QueueState
import de.yurtemre.sonora.shared.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JamMirrorModeTest {
    private val speed = mutableListOf<Boolean>()
    private val silence = mutableListOf<Boolean>()
    private val mode = JamMirrorMode(setNormalSpeed = { speed += it }, setSkipSilence = { silence += it })

    @Test fun skipSilenceIsOffWhileMirroringAndTheUsersValueComesBack() {
        mode.userSkipSilence = true
        assertEquals(listOf(true), silence)
        mode.begin()
        assertEquals(listOf(true, false), silence)
        assertEquals(listOf(true), speed)
        mode.userSkipSilence = true // the setting is re-read while in the Jam: stays off
        assertEquals(false, silence.last())
        mode.end()
        assertEquals(true, silence.last())
        assertEquals(listOf(true, false), speed)
        mode.end() // idempotent
        assertEquals(2, speed.size)
    }

    private val playing = QueueState(entries = listOf(QueueEntry(7, 1), QueueEntry(8, 2)), currentIndex = 0, nextUid = 9)

    @Test fun samePlayingSongIsKeptAndAPlayRequestHonoured() {
        val edit = mirrorEdit(applied = playing, latest = playing, mirroring = true, ids = listOf(1, 3), index = 0, positionMs = 5_000, play = true)
        edit as MirrorEdit.Keep
        assertTrue(edit.play)
        assertEquals(listOf(QueueEntry(7, 1), QueueEntry(9, 3)), edit.state.entries)
        assertEquals(RepeatMode.Off, edit.state.repeat)
    }

    @Test fun aLoadStillBeingBuiltIsReplacedWithTheGivenPositionAndPlay() {
        // The first mirror load is still being built: never fall back to "position 0, whatever the player had".
        val pending = playing.copy(nextUid = 20)
        val edit = mirrorEdit(applied = playing, latest = pending, mirroring = true, ids = listOf(1, 2), index = 0, positionMs = 5_000, play = true)
        assertEquals(MirrorEdit.Load::class, edit::class)
        edit as MirrorEdit.Load
        assertEquals(5_000L, edit.positionMs)
        assertTrue(edit.play)
    }

    @Test fun firstMirrorLoadIsALoad() {
        val edit = mirrorEdit(applied = playing, latest = playing, mirroring = false, ids = listOf(1), index = 0, positionMs = 0, play = false)
        assertTrue(edit is MirrorEdit.Load)
    }
}
