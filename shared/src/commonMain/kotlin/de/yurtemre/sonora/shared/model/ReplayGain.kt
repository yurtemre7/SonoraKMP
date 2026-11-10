package de.yurtemre.sonora.shared.model

import kotlinx.serialization.Serializable

/** ReplayGain mode (SPEC §5.1, PLAY-09); stored as `replay_gain_mode`. */
enum class ReplayGainMode(override val storageName: String) : StorageEnum {
    Off("off"), Track("track"), Album("album");

    companion object {
        fun fromStorage(value: String?): ReplayGainMode = enumFromStorage(value, Off)
    }
}

/** ReplayGain tag values in dB / linear peak (audit/data §9.1). */
@Serializable
data class ReplayGainInfo(
    val trackGain: Double? = null,
    val albumGain: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
)
