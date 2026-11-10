package de.yurtemre.sonora.shared.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/** Every DataStore key of SPEC §5.3 (file `sonora_settings`), names identical to Flutter where they exist. */
object SettingsKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val THEME_COLOR_SOURCE = stringPreferencesKey("theme_color_source")
    /** Legacy mirror (BAK-05); written on every color-source change. */
    val USE_MATERIAL_YOU = booleanPreferencesKey("use_material_you")
    /** Legacy mirror (BAK-05); written on every color-source change. */
    val USE_DYNAMIC_THEME = booleanPreferencesKey("use_dynamic_theme")
    val DEFAULT_THEME_COLOR = longPreferencesKey("default_theme_color")
    val AMOLED_DARK = booleanPreferencesKey("amoled_dark")
    val BLURRED_BACKGROUNDS = booleanPreferencesKey("blurred_backgrounds")
    val NOW_PLAYING_STYLE = stringPreferencesKey("now_playing_style")
    val PREFER_LOCAL_ARTIST_IMAGES = booleanPreferencesKey("prefer_local_artist_images")
    val SHOW_VISUALIZER = booleanPreferencesKey("show_visualizer")
    val IMMERSIVE_MODE = booleanPreferencesKey("immersive_mode")
    val USE_GREETING_TITLE = booleanPreferencesKey("use_greeting_title")
    val USER_NAME = stringPreferencesKey("user_name")
    val APP_LOCALE = stringPreferencesKey("app_locale")
    val DEFAULT_START_PAGE = intPreferencesKey("default_start_page")
    val KEEP_PLAYING_ON_CLOSE = booleanPreferencesKey("keep_playing_on_close")
    val RESTORE_LAST_PLAYED_SONG = booleanPreferencesKey("restore_last_played_song")
    val REPLAY_GAIN_MODE = stringPreferencesKey("replay_gain_mode")
    val PAUSE_ON_DUCK = booleanPreferencesKey("pause_on_duck")
    val PAUSE_ON_DISCONNECT = booleanPreferencesKey("pause_on_disconnect")
    val RESUME_ON_CONNECT = booleanPreferencesKey("resume_on_connect")
    val MFX_BLUETOOTH_PATCH = booleanPreferencesKey("mfx_bluetoothMfxPatch")
    val SLEEP_TIMER_DEFAULT_MINUTES = intPreferencesKey("sleep_timer_default_minutes")
    val SLEEP_TIMER_FADE_OUT_SECONDS = intPreferencesKey("sleep_timer_fade_out_seconds")
    val SLEEP_TIMER_FINISH_SONG = booleanPreferencesKey("sleep_timer_finish_song")
    /** Native-only: persisted repeat mode (`off`/`all`/`one`). */
    val REPEAT_MODE = stringPreferencesKey("repeat_mode")
    val FILTER_TITLE_FEATURES = booleanPreferencesKey("filter_title_features")
    val FILTER_TITLE_ARTIST = booleanPreferencesKey("filter_title_artist")
    val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
    val INCLUDE_PRERELEASES = booleanPreferencesKey("include_prereleases")
    val SONG_SORT_BY = stringPreferencesKey("song_sort_by")
    val SONG_SORT_ASCENDING = booleanPreferencesKey("song_sort_ascending")
    val ALBUM_SORT_BY = stringPreferencesKey("album_sort_by")
    val ALBUM_SORT_ASCENDING = booleanPreferencesKey("album_sort_ascending")
    val ARTIST_SORT_BY = stringPreferencesKey("artist_sort_by")
    val ARTIST_SORT_ASCENDING = booleanPreferencesKey("artist_sort_ascending")
    val PLAYLIST_SORT_BY = stringPreferencesKey("playlist_sort_by")
    val PLAYLIST_SORT_ASCENDING = booleanPreferencesKey("playlist_sort_ascending")
    val FAVORITES_SORT_BY = stringPreferencesKey("favorites_sort_by")
    val FAVORITES_SORT_ASCENDING = booleanPreferencesKey("favorites_sort_ascending")
    val SONG_ACTIVITY_VIEW = stringPreferencesKey("song_activity_view")
    val VOLUME = doublePreferencesKey("volume")
    val SPEED = doublePreferencesKey("speed")
    val MFX_PRESET_NAME = stringPreferencesKey("mfx_preset_name")
    val MFX_REVERB_ENABLED = booleanPreferencesKey("mfx_isReverbEnabled")
    val MFX_LOFI_ENABLED = booleanPreferencesKey("mfx_isLofiEnabled")
    val MFX_BASS_BOOST_ENABLED = booleanPreferencesKey("mfx_isBassBoostEnabled")
    val MFX_CUSTOM_EQ_ENABLED = booleanPreferencesKey("mfx_isCustomEqEnabled")
    /** JSON array of doubles. */
    val MFX_CUSTOM_EQ_GAINS = stringPreferencesKey("mfx_customEqGains")

    // Playback state (PLAY-24, audit/playback §11) — accessed via PlaybackStateStore.
    val LAST_PLAYED_SONG_ID = longPreferencesKey("last_played_song_id")
    /** Comma-separated song ids. */
    val LAST_PLAYED_QUEUE_IDS = stringPreferencesKey("last_played_queue_ids")
    /** Comma-separated song ids. */
    val LAST_PLAYED_ORIGINAL_QUEUE_IDS = stringPreferencesKey("last_played_original_queue_ids")
    val LAST_PLAYED_POSITION_MS = longPreferencesKey("last_played_position_ms")
    val LAST_PLAYED_IS_SHUFFLED = booleanPreferencesKey("last_played_is_shuffled")

    val SCAN_FOLDER_PATH = stringPreferencesKey("scan_folder_path")
    val SCAN_FOLDER_URI = stringPreferencesKey("scan_folder_uri")
    val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
    val LAST_SYNC_DURATION_MS = longPreferencesKey("last_sync_duration_ms")
    val POSTPONE_SYNC_UNTIL = longPreferencesKey("postpone_sync_until")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val COLORLESS_ARTWORK_PATHS = stringSetPreferencesKey("colorless_artwork_paths")
    val LAST_UPDATE_CHECK_MS = longPreferencesKey("last_update_check_ms")
    val UPDATE_RATE_LIMIT_RESET_MS = longPreferencesKey("update_rate_limit_reset_ms")
}
