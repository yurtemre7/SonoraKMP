package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.JamState
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.testing.testSong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertTrue

/** Fakes, clock and helpers shared by the Jam controller tests (deterministic: virtual time, no sockets). */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class JamControllerTestBase {
    protected val a = testSong(1, "A")
    protected val b = testSong(2, "B")
    protected val c = testSong(3, "C")
    /** Only the host has this one. */
    protected val missing = testSong(99, "Missing")

    protected val transport = FakeHostTransport()
    protected val connector = FakeConnector()
    protected val skips = mutableListOf<Long>()
    protected val logs = mutableListOf<String>()
    protected var speakerDelay = 0
    protected var codes = 0
    protected lateinit var playback: RecordingPlayback

    protected fun TestScope.controller(library: List<Song> = listOf(a, b, c)): JamController {
        val now = { testScheduler.currentTime }
        playback = RecordingPlayback(now)
        return JamController(
            backgroundScope, playback,
            JamEnvironment(
                hostTransport = { transport },
                connector = connector,
                library = { library },
                nowMs = now,
                speakerDelayMs = { speakerDelay },
                recordSkip = { skips += it },
                deviceName = { "Guest phone" },
                newCode = { (123_456 + codes++).toString() },
                debugLog = { logs += it },
            ),
        )
    }

    protected fun TestScope.state(
        vararg songs: Song,
        index: Int = 0,
        playing: Boolean = true,
        positionMs: Long = 0,
        hostTimeMs: Long = testScheduler.currentTime,
        canControl: Boolean = false,
    ) = JamState(songs.map { it.jam() }, index, playing, positionMs, hostTimeMs, guestsCanAdd = true, guestsCanControl = canControl)

    /** Joins with a fresh link; returns it. */
    protected suspend fun TestScope.joined(jam: JamController, link: FakeLink = FakeLink(offsetMs = 0)): FakeLink {
        connector.links.addLast(link)
        jam.join("10.0.0.2", 41_234, "123456", "Host phone")
        runCurrent()
        return link
    }

    protected fun assertNoEffects() =
        assertTrue("speed/pitch/effects touched: ${playback.effectCalls}", playback.effectCalls.isEmpty())
}
