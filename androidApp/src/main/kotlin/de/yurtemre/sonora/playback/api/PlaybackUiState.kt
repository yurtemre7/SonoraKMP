package de.yurtemre.sonora.playback.api

import de.yurtemre.sonora.shared.model.EqMode
import de.yurtemre.sonora.shared.model.MfxPreset
import de.yurtemre.sonora.shared.model.RepeatMode
import de.yurtemre.sonora.shared.model.Song

/** Transport/now-playing state (PLAY-01..11, PLAY-25). Position is a separate flow. */
data class PlayerUiState(
    val isConnected: Boolean = false,
    val currentSong: Song? = null,
    /** Entry uid of the current queue item (`q:<uid>` media id). */
    val currentEntryUid: Long? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Queue ended with repeat off (mini player shows replay icon). */
    val isCompleted: Boolean = false,
    /** Effective duration = round(rawMs / speed) (PLAY-13). */
    val durationMs: Long = 0,
    val repeat: RepeatMode = RepeatMode.Off,
    val isShuffled: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    /** In-app user volume 0..1 (PLAY-11). */
    val userVolume: Float = 1f,
    val playlistContext: String? = null,
)

data class QueueItemUi(val entryUid: Long, val song: Song)

/** Queue view (PLAY-02..06). */
data class QueueUiState(
    val items: List<QueueItemUi> = emptyList(),
    val currentIndex: Int = -1,
    val isShuffled: Boolean = false,
    val playlistContext: String? = null,
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/** Sleep timer (PLAY-26/27). */
sealed interface SleepTimerUiState {
    data object Inactive : SleepTimerUiState

    data class Countdown(
        val remainingMs: Long,
        val totalMs: Long,
        val fadeSeconds: Int,
        val isFading: Boolean,
        /** When the countdown ends, the current song plays to its end before stopping. */
        val finishSong: Boolean = false,
    ) : SleepTimerUiState

    data class FinishSong(val fadeSeconds: Int, val isFading: Boolean) : SleepTimerUiState
}

/** Speed/pitch/EQ effects (PLAY-12..16). */
data class MfxUiState(
    val preset: MfxPreset = MfxPreset.entries.first { it.isNormal },
    /** Custom speed [0.5, 2.0]; disabled while a non-normal preset is active. */
    val customSpeed: Float = 1f,
    val bassBoost: Boolean = false,
    val warmth: Boolean = false,
    val loFi: Boolean = false,
    val customEqEnabled: Boolean = false,
    val customEqGains: List<Float> = emptyList(),
    val eqBandCenterHz: List<Int> = emptyList(),
    val eqMinLevelMb: Int = 0,
    val eqMaxLevelMb: Int = 0,
    val isEqAvailable: Boolean = false,
    val activeEqMode: EqMode = EqMode.Off,
) {
    val effectiveSpeed: Float get() = if (preset.isNormal) customSpeed else preset.speed
    val isCustomSpeedEnabled: Boolean get() = preset.isNormal
}
