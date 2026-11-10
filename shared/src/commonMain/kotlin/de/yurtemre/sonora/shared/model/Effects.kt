package de.yurtemre.sonora.shared.model

/** MFX speed+pitch presets (SPEC §5.1, audit/playback); storage = Flutter enum name in `mfx_preset_name`. */
enum class MfxPreset(override val storageName: String, val speed: Float) : StorageEnum {
    SuperSlowed("superSlowed", 0.70f),
    Slowed075("slowed075", 0.75f),
    Slowed080("slowed080", 0.80f),
    Slowed("slowed", 0.85f),
    Slowed090("slowed090", 0.90f),
    Slowed095("slowed095", 0.95f),
    Normal("normal", 1.00f),
    SpedUp105("spedUp105", 1.05f),
    SpedUp110("spedUp110", 1.10f),
    SpedUp115("spedUp115", 1.15f),
    SpedUp120("spedUp120", 1.20f),
    SpedUp("spedUp", 1.25f),
    Nightcore("nightcore", 1.30f),
    SuperSpedUp("superSpedUp", 1.50f);

    val pitch: Float get() = speed
    val isNormal: Boolean get() = speed == 1.0f

    companion object {
        /** Returns null for unset/unknown (Flutter: preset none). */
        fun fromStorage(value: String?): MfxPreset? = entries.firstOrNull { it.storageName == value }
    }
}

/** Custom-EQ preset fractions, 5 values mapped onto N bands (audit/playback, PLAY-15). */
enum class EqPreset(override val storageName: String, val fractions: List<Double>) : StorageEnum {
    Flat("flat", listOf(0.0, 0.0, 0.0, 0.0, 0.0)),
    Pop("pop", listOf(-0.2, 0.2, 0.5, 0.2, -0.2)),
    Rock("rock", listOf(0.5, 0.3, -0.3, 0.3, 0.5)),
    Jazz("jazz", listOf(0.4, 0.2, -0.2, 0.2, 0.4)),
    Classical("classical", listOf(0.5, 0.3, -0.2, 0.4, 0.4));

    companion object {
        fun fromStorage(value: String?): EqPreset = enumFromStorage(value, Flat)
    }
}

/** Effective equalizer mode derived from MFX toggles (audit/playback, PLAY-13..15). Not persisted. */
enum class EqMode { Off, Custom, BassBoost, Warmth, LoFi }
