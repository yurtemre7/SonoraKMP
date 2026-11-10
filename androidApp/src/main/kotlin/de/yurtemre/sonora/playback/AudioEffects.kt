package de.yurtemre.sonora.playback

import android.media.audiofx.Equalizer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import de.yurtemre.sonora.shared.audio.EqGains
import de.yurtemre.sonora.shared.audio.SpeedPresets
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.model.EqMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * PLAY-12..16: Equalizer bound to the player's audio session (recreated on change) and speed/pitch via
 * [PlaybackParameters]. All EQ errors are swallowed; [isEqualizerSupported] becomes false when creation fails.
 */
@OptIn(UnstableApi::class)
class AudioEffects(
    private val player: ExoPlayer,
    private val settings: Flow<AppSettings>,
    private val scope: CoroutineScope,
) {
    private val _isEqualizerSupported = MutableStateFlow(true)
    val isEqualizerSupported: StateFlow<Boolean> = _isEqualizerSupported.asStateFlow()

    private val _bandCount = MutableStateFlow(0)
    /** Number of device EQ bands (0 until created). */
    val bandCount: StateFlow<Int> = _bandCount.asStateFlow()

    /** Device EQ band layout (centers in Hz, level range in mB); null until created. */
    data class EqInfo(val centersHz: List<Int>, val minMb: Int, val maxMb: Int)

    private val _eqInfo = MutableStateFlow<EqInfo?>(null)
    val eqInfo: StateFlow<EqInfo?> = _eqInfo.asStateFlow()

    private var equalizer: Equalizer? = null
    private var current: AppSettings? = null
    private var job: Job? = null

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
            recreate(audioSessionId)
        }
    }

    fun start() {
        if (job != null) return
        player.addAnalyticsListener(analyticsListener)
        recreate(player.audioSessionId)
        job = scope.launch { settings.collect { apply(it) } }
    }

    /** Releases the equalizer and stops observing. */
    fun stop() {
        job?.cancel(); job = null
        player.removeAnalyticsListener(analyticsListener)
        releaseEq()
    }

    /** Re-apply speed/pitch (call per track, PLAY-13). */
    fun reapplySpeed() { current?.let(::applySpeed) }

    private fun recreate(sessionId: Int) {
        releaseEq()
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        equalizer = try {
            Equalizer(0, sessionId).also {
                _bandCount.value = it.numberOfBands.toInt()
                val r = it.bandLevelRange
                _eqInfo.value = EqInfo(List(it.numberOfBands.toInt()) { b -> it.getCenterFreq(b.toShort()) / 1000 }, r[0].toInt(), r[1].toInt())
                _isEqualizerSupported.value = true
            }
        } catch (t: Throwable) {
            _isEqualizerSupported.value = false
            null
        }
        current?.let(::applyEq)
    }

    private fun releaseEq() {
        try { equalizer?.release() } catch (_: Throwable) {}
        equalizer = null
    }

    private fun apply(s: AppSettings) {
        current = s
        applySpeed(s)
        applyEq(s)
    }

    private fun applySpeed(s: AppSettings) {
        val (speed, pitch) = SpeedPresets.playbackParameters(s.mfxPreset, SpeedPresets.clampSpeed(s.speed))
        val params = PlaybackParameters(speed.toFloat(), pitch.toFloat())
        if (player.playbackParameters != params) player.playbackParameters = params
    }

    private fun applyEq(s: AppSettings) {
        val eq = equalizer ?: return
        try {
            val mode = EqGains.mode(s.mfxLofiEnabled, s.mfxReverbEnabled, s.mfxBassBoostEnabled, s.mfxCustomEqEnabled)
            if (mode == EqMode.Off) { eq.enabled = false; return }
            val range = eq.bandLevelRange
            val min = range[0] / 100.0
            val max = range[1] / 100.0
            val n = eq.numberOfBands.toInt()
            val centers = List(n) { eq.getCenterFreq(it.toShort()) / 1000.0 }
            val gains = EqGains.bandGains(mode, centers, min, max, s.bluetoothMfxPatch, s.mfxCustomEqGains)
            gains.forEachIndexed { i, g -> if (g != null) eq.setBandLevel(i.toShort(), EqGains.toMillibels(g)) }
            eq.enabled = true
        } catch (_: Throwable) {
        }
    }
}
