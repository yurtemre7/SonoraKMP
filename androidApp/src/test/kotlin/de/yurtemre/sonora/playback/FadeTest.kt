package de.yurtemre.sonora.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import de.yurtemre.sonora.shared.audio.FadeCurve
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FadeTest {
    private lateinit var exo: ExoPlayer
    private val scope = TestScope(StandardTestDispatcher())
    private lateinit var player: SonoraForwardingPlayer

    @Before fun setUp() {
        exo = PlayerFactory.create(ApplicationProvider.getApplicationContext())
        player = SonoraForwardingPlayer(exo, scope)
    }

    @After fun tearDown() = exo.release()

    private fun step() { scope.advanceTimeBy(FadeCurve.STEP_MS); scope.runCurrent() }

    @Test fun fadeRampAndReverse() { fadeInRampFollowsCurveUnderVirtualTime(); playMidFadeOutReversesWithoutPausing() }

    private fun fadeInRampFollowsCurveUnderVirtualTime() {
        player.play()
        assertEquals(0f, exo.volume, 0f)
        FadeCurve.ramp(0.0, 1.0).forEach { expected ->
            step()
            assertEquals(expected.toFloat(), exo.volume, 1e-6f)
        }
        assertFalse(player.isFading)
        assertTrue(exo.playWhenReady)
    }

    private fun playMidFadeOutReversesWithoutPausing() {
        player.play(); repeat(FadeCurve.STEPS) { step() }
        player.pause(); repeat(10) { step() }
        val mid = exo.volume
        assertTrue(mid in 0.01f..0.99f)
        player.play()
        repeat(FadeCurve.STEPS) { step() }
        assertEquals(1f, exo.volume, 1e-6f)
        assertTrue(exo.playWhenReady)
    }

    @Test fun volumeComposition() { lowUserVolumeIsRestoredExactlyAfterFade(); volumeComposesUserFadeAndReplayGainCappedAtOne() }

    private fun lowUserVolumeIsRestoredExactlyAfterFade() {
        player.volume = 0.04f
        player.play(); repeat(FadeCurve.STEPS) { step() }
        assertEquals(0.04f, exo.volume, 1e-6f)
        player.pause(); repeat(FadeCurve.STEPS) { step() }
        assertFalse(exo.playWhenReady)
        assertEquals(0.04f, player.volume, 1e-6f)
        assertEquals(0.04f, exo.volume, 1e-6f)
        player.play(); repeat(FadeCurve.STEPS) { step() }
        assertEquals(0.04f, exo.volume, 1e-6f)
    }

    private fun volumeComposesUserFadeAndReplayGainCappedAtOne() {
        player.volume = 0.5f
        player.volumes.replayGain = 0.5
        assertEquals(0.25f, exo.volume, 1e-6f)
        player.volumes.fade = 0.5
        assertEquals(0.125f, exo.volume, 1e-6f)
        player.volumes.fade = 1.0
        player.volumes.replayGain = 4.0
        assertEquals(1f, exo.volume, 0f)
    }
}
