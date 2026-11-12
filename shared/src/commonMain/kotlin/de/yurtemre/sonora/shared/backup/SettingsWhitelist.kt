package de.yurtemre.sonora.shared.backup

import de.yurtemre.sonora.shared.model.MfxPreset
import de.yurtemre.sonora.shared.model.UpdateChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Backup settings whitelist (SPEC §5.5, audit/data §12.2, BAK-05).
 *
 * Two key spaces: the *backup* map uses Flutter key names and Flutter types (what Flutter 1.28 reads and
 * writes), the *stored* map uses KMP DataStore key names with values already converted to backup types
 * (Long → [SettingValue.Int], the `mfx_customEqGains` JSON string → [SettingValue.Str]).
 */
object SettingsWhitelist {
    const val THEME_COLOR_SOURCE = "theme_color_source"
    const val USE_MATERIAL_YOU = "use_material_you"
    const val USE_DYNAMIC_THEME = "use_dynamic_theme"
    const val MFX_PRESET_NAME = "mfx_preset_name"
    const val MFX_CUSTOM_EQ_GAINS = "mfx_customEqGains"
    const val APP_LOCALE = "app_locale"
    const val UPDATE_CHANNEL = "update_channel"
    const val LEGACY_INCLUDE_PRERELEASES = "include_prereleases"

    private val B = PrefType.Boolean
    private val I = PrefType.Integer
    private val D = PrefType.Decimal
    private val S = PrefType.String
    private val SL = PrefType.StringList

    /** Exactly Flutter 1.28.1's `BackupService._settingKeys`, in its order. */
    val flutterKeys: Map<String, PrefType> = linkedMapOf(
        "theme_mode" to S, USE_DYNAMIC_THEME to B, USE_MATERIAL_YOU to B, "amoled_dark" to B,
        "blurred_backgrounds" to B, "dynamic_theme_color" to I, "default_theme_color" to I,
        "now_playing_style" to S, "show_visualizer" to B, "immersive_mode" to B,
        "prefer_local_artist_images" to B, APP_LOCALE to S, "default_start_page" to I, "user_name" to S,
        "use_greeting_title" to B,
        "sleep_timer_default_minutes" to I, "sleep_timer_fade_out_seconds" to I, "sleep_timer_finish_song" to B,
        "song_sort_by" to S, "song_sort_ascending" to B, "album_sort_by" to S, "album_sort_ascending" to B,
        "artist_sort_by" to S, "artist_sort_ascending" to B, "playlist_sort_by" to S,
        "playlist_sort_ascending" to B, "favorites_sort_by" to S, "favorites_sort_ascending" to B,
        "song_activity_view" to S, "sort_by" to S, "sort_ascending" to B, "sort_by_songs" to S,
        "sort_ascending_songs" to B,
        "keep_playing_on_close" to B, "restore_last_played_song" to B, "pause_on_duck" to B,
        "replay_gain_mode" to S, "pause_on_disconnect" to B, "resume_on_connect" to B,
        "filter_title_features" to B, "filter_title_artist" to B, "filter_title_video_tags" to B, "auto_check_updates" to B,
        "volume" to D, "speed" to D, "pitch" to D, MFX_PRESET_NAME to S, "mfx_preset" to D, "mfx_isSlowed" to B,
        "mfx_isSuperSlowed" to B, "mfx_isSpedUp" to B, "mfx_isSuperSpedUp" to B, "mfx_isNightcore" to B,
        "mfx_isReverbEnabled" to B, "mfx_isLofiEnabled" to B, "mfx_isBassBoostEnabled" to B,
        "mfx_isCustomEqEnabled" to B, "mfx_bluetoothMfxPatch" to B, MFX_CUSTOM_EQ_GAINS to SL,
        "eq_current_preset" to S, "eq_custom_gains" to SL, "eq_enabled" to B,
    )

    /** Flutter keys read on import only (mapped or ignored), never written by KMP (SPEC §5.5). */
    val importOnlyKeys: Set<String> = setOf(
        "dynamic_theme_color", "sort_by", "sort_ascending", "sort_by_songs", "sort_ascending_songs",
        "mfx_preset", "mfx_isSlowed", "mfx_isSuperSlowed", "mfx_isSpedUp", "mfx_isSuperSpedUp",
        "mfx_isNightcore", "eq_current_preset", "eq_custom_gains", "eq_enabled",
    )

    /** Keys KMP writes on export (backup types); Flutter ignores [THEME_COLOR_SOURCE] as unknown. */
    val exportKeys: Map<String, PrefType> =
        flutterKeys.filterKeys { it !in importOnlyKeys } + (THEME_COLOR_SOURCE to S) + (UPDATE_CHANNEL to S)

    /** Keys accepted (and type-checked) by the KMP parser. */
    val parseKeys: Map<String, PrefType> =
        flutterKeys + (THEME_COLOR_SOURCE to S) + (UPDATE_CHANNEL to S) + (LEGACY_INCLUDE_PRERELEASES to B)

    /** Flutter keys removed on restore when a `settings` section lacks them (audit/data §12.5). */
    val flutterRemovedWhenAbsent = listOf(MFX_PRESET_NAME, "mfx_preset", MFX_CUSTOM_EQ_GAINS)

    /** KMP stored keys removed on restore when the backup's settings lack them (BAK-04). */
    val removedWhenAbsent = listOf(MFX_PRESET_NAME, MFX_CUSTOM_EQ_GAINS)

    /** Stored KMP settings → backup `settings` section. Wrong-typed or non-whitelisted values are omitted. */
    fun toBackup(stored: Map<String, SettingValue>): Map<String, SettingValue> {
        val out = LinkedHashMap<String, SettingValue>()
        for ((key, type) in exportKeys) {
            val value = stored[key] ?: continue
            if (key == MFX_CUSTOM_EQ_GAINS) {
                gainsToList(value)?.let { out[key] = it }
            } else if (value.matches(type)) {
                out[key] = value
            }
        }
        return out
    }

    /**
     * Backup `settings` section (Flutter or KMP) → stored KMP values, applying the BAK-05 legacy mappings.
     * Import-only keys are consumed here and never returned.
     */
    fun fromBackup(backup: Map<String, SettingValue>): Map<String, SettingValue> {
        val out = LinkedHashMap<String, SettingValue>()
        for ((key, value) in backup) {
            if (key in importOnlyKeys || key == LEGACY_INCLUDE_PRERELEASES) continue
            val type = parseKeys[key] ?: continue
            if (key == MFX_CUSTOM_EQ_GAINS) {
                gainsToStored(value)?.let { out[key] = it }
            } else if (value.matches(type)) {
                out[key] = value
            }
        }
        if (THEME_COLOR_SOURCE !in out) legacyColorSource(backup)?.let { out[THEME_COLOR_SOURCE] = SettingValue.Str(it) }
        if (UPDATE_CHANNEL !in out && (backup[LEGACY_INCLUDE_PRERELEASES] as? SettingValue.Bool)?.value == true) {
            out[UPDATE_CHANNEL] = SettingValue.Str(UpdateChannel.PreRelease.storageName)
        }
        if (MFX_PRESET_NAME !in out) legacyPreset(backup)?.let { out[MFX_PRESET_NAME] = SettingValue.Str(it.storageName) }
        return out
    }

    /** audit/settings §9.2: materialYou → system, else dynamic (default true) → album art, else custom. */
    internal fun legacyColorSource(backup: Map<String, SettingValue>): String? {
        val my = (backup[USE_MATERIAL_YOU] as? SettingValue.Bool)?.value
        val dyn = (backup[USE_DYNAMIC_THEME] as? SettingValue.Bool)?.value
        if (my == null && dyn == null) return null
        return when {
            my == true -> "system"
            dyn ?: true -> "album_art"
            else -> "custom"
        }
    }

    /** audit/playback §5: legacy speed, then booleans (superSlowed > slowed > superSpedUp > spedUp > nightcore). */
    internal fun legacyPreset(backup: Map<String, SettingValue>): MfxPreset? {
        (backup["mfx_preset"] as? SettingValue.Decimal)?.value?.let { speed ->
            MfxPreset.entries.firstOrNull { kotlin.math.abs(it.speed - speed) < 1e-3 }?.let { return it }
        }
        fun on(key: String) = (backup[key] as? SettingValue.Bool)?.value == true
        return when {
            on("mfx_isSuperSlowed") -> MfxPreset.SuperSlowed
            on("mfx_isSlowed") -> MfxPreset.Slowed
            on("mfx_isSuperSpedUp") -> MfxPreset.SuperSpedUp
            on("mfx_isSpedUp") -> MfxPreset.SpedUp
            on("mfx_isNightcore") -> MfxPreset.Nightcore
            else -> null
        }
    }

    private fun gainsToList(value: SettingValue): SettingValue.StrList? = when (value) {
        is SettingValue.StrList -> value
        is SettingValue.Str -> runCatching {
            SettingValue.StrList(Json.parseToJsonElement(value.value).jsonArray.map { dartDouble(it.jsonPrimitive.doubleOrNull!!) })
        }.getOrNull()
        else -> null
    }

    private fun gainsToStored(value: SettingValue): SettingValue.Str? = when (value) {
        is SettingValue.StrList -> SettingValue.Str(
            JsonArray(value.value.mapNotNull { it.trim().toDoubleOrNull() }.map(::JsonPrimitive)).toString()
        )
        is SettingValue.Str -> value
        else -> null
    }

    /** Dart `double.toString()` for finite values in the gain range (always has a fraction, e.g. `1.0`). */
    private fun dartDouble(d: Double): String {
        val s = d.toString()
        return if (s.contains('.') || s.contains('E') || s.contains('e')) s else "$s.0"
    }
}
