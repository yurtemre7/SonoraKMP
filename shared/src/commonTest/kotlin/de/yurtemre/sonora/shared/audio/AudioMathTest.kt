package de.yurtemre.sonora.shared.audio

import de.yurtemre.sonora.shared.model.EqMode
import de.yurtemre.sonora.shared.model.EqPreset
import de.yurtemre.sonora.shared.model.MfxPreset
import de.yurtemre.sonora.shared.model.ReplayGainInfo
import de.yurtemre.sonora.shared.model.ReplayGainMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioMathTest {
    private val eps = 1e-3

    @Test fun replayGainMultiplier() {
        val m = ReplayGainMath.multiplier(ReplayGainInfo(trackGain = -6.0), ReplayGainMode.Track)
        assertEquals(0.501, m, eps)
        assertEquals(0.2506, ReplayGainMath.effectiveVolume(0.5, m), 1e-4)
        assertEquals(m, ReplayGainMath.multiplier(ReplayGainInfo(trackGain = -6.0), ReplayGainMode.Album))
        assertEquals(1.0, ReplayGainMath.multiplier(null, ReplayGainMode.Track))
        assertEquals(1.0, ReplayGainMath.multiplier(ReplayGainInfo(trackGain = -6.0), ReplayGainMode.Off))
        assertEquals(1.0, ReplayGainMath.multiplier(ReplayGainInfo(), ReplayGainMode.Album))
    }

    @Test fun clipGuardAndCap() {
        val m = ReplayGainMath.multiplier(ReplayGainInfo(trackGain = 6.0, trackPeak = 0.8), ReplayGainMode.Track)
        assertEquals(1.25, m, 1e-9)
        assertEquals(1.0, ReplayGainMath.effectiveVolume(1.0, m))
        assertEquals(1.0 / 0.9, ReplayGainMath.multiplier(ReplayGainInfo(trackGain = 6.0, albumPeak = 0.9), ReplayGainMode.Track), 1e-9)
    }

    @Test fun speedPresets() {
        assertEquals(14, SpeedPresets.table.size)
        assertEquals(1.30 to 1.30, SpeedPresets.playbackParameters(MfxPreset.Nightcore, 1.5))
        assertEquals(1.5 to 1.0, SpeedPresets.playbackParameters(MfxPreset.Normal, 1.5))
        for (p in MfxPreset.entries) assertEquals(SpeedPresets.speedOf(p), p.speed.toDouble(), 1e-6)
        assertEquals(MfxPreset.SpedUp, SpeedPresets.fromSpeed(1.2505))
        assertNull(SpeedPresets.fromSpeed(1.27))
        assertEquals(2.0, SpeedPresets.clampSpeed(3.0))
        assertEquals(76923L, SpeedPresets.effectiveDurationMs(100_000, 1.3))
    }

    @Test fun eqModesStandardAndBluetooth() {
        fun g(mode: EqMode, f: Double, bt: Boolean) = EqGains.modeGain(mode, f, -15.0, 15.0, bt)!!
        assertEquals(12.0, g(EqMode.BassBoost, 60.0, false), eps)
        assertEquals(6.0, g(EqMode.BassBoost, 60.0, true), eps)
        assertEquals(7.5, g(EqMode.Warmth, 60.0, false), eps)
        assertEquals(-7.5, g(EqMode.Warmth, 14000.0, false), eps)
        assertEquals(4.5, g(EqMode.Warmth, 60.0, true), eps)
        assertEquals(-3.75, g(EqMode.Warmth, 14000.0, true), eps)
        assertEquals(-15.0, g(EqMode.LoFi, 3600.0, false), eps)
        assertEquals(-8.25, g(EqMode.LoFi, 14000.0, true), eps)
        assertEquals(-4.5, g(EqMode.LoFi, 60.0, false), eps)
        assertEquals(-3.0, g(EqMode.LoFi, 60.0, true), eps)
        assertEquals(2.25, g(EqMode.LoFi, 910.0, true), eps)
        assertEquals(EqMode.LoFi, EqGains.mode(lofi = true, warmth = true, bassBoost = true, customEq = true))
        assertEquals(EqMode.Custom, EqGains.mode(false, false, false, true))
        assertEquals(listOf(15.0, -3.0, null), EqGains.bandGains(EqMode.Custom, listOf(60.0, 230.0, 910.0), -15.0, 15.0, true, listOf(20.0, -3.0)))
        assertEquals(1200.toShort(), EqGains.toMillibels(12.0))
    }

    @Test fun presetMapping() {
        assertEquals(listOf(7.5, 4.5, -4.5, 4.5, 7.5), EqGains.presetGains(EqPreset.Rock, 5, -15.0, 15.0))
        assertEquals(
            listOf(-3.0, -3.0, 3.0, 3.0, 7.5, 7.5, 3.0, 3.0, -3.0, -3.0).map { it },
            EqGains.presetGains(EqPreset.Pop, 10, -15.0, 15.0).map { kotlin.math.round(it * 1000) / 1000 },
        )
    }

    @Test fun fadeSteps() {
        val up = FadeCurve.ramp(0.0, 0.8)
        assertEquals(20, up.size)
        for (i in up.indices) assertEquals(0.8 * (i + 1) / 20, up[i], 1e-9)
        assertEquals(0.0, FadeCurve.ramp(0.6, 0.0).last(), 1e-12)
        assertEquals(20L, FadeCurve.STEP_MS)
        assertEquals(1.0, FadeCurve.restoreVolume(0.05))
        assertEquals(0.3, FadeCurve.fadeInTarget(0.3, 0.0))
    }
}
