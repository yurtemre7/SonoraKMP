package de.yurtemre.sonora.shared.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import de.yurtemre.sonora.shared.model.*
import de.yurtemre.sonora.shared.settings.SettingsKeys as K
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** DataStore-backed settings (SPEC §5.3, audit/settings §1). Defaults come from [AppSettings]. */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.map(::read).distinctUntilChanged()

    // ---- Appearance ----
    suspend fun setThemeMode(v: ThemeModeSetting) = put { it[K.THEME_MODE] = v.storageName }

    /** Writes `theme_color_source` plus the Flutter legacy mirrors (BAK-05, audit/settings §9). */
    suspend fun setThemeColorSource(v: ThemeColorSource) = put {
        it[K.THEME_COLOR_SOURCE] = v.storageName
        val (materialYou, dynamic) = legacyMirror(v)
        it[K.USE_MATERIAL_YOU] = materialYou
        it[K.USE_DYNAMIC_THEME] = dynamic
    }
    suspend fun setDefaultThemeColor(argb: Long) = put { it[K.DEFAULT_THEME_COLOR] = argb }
    suspend fun setAmoledDark(v: Boolean) = put { it[K.AMOLED_DARK] = v }
    suspend fun setBlurredBackgrounds(v: Boolean) = put { it[K.BLURRED_BACKGROUNDS] = v }
    suspend fun setNowPlayingStyle(v: NowPlayingStyle) = put { it[K.NOW_PLAYING_STYLE] = v.storageName }
    suspend fun setPreferLocalArtistImages(v: Boolean) = put { it[K.PREFER_LOCAL_ARTIST_IMAGES] = v }
    suspend fun setShowVisualizer(v: Boolean) = put { it[K.SHOW_VISUALIZER] = v }
    suspend fun setImmersiveMode(v: Boolean) = put { it[K.IMMERSIVE_MODE] = v }
    suspend fun setUseGreetingTitle(v: Boolean) = put { it[K.USE_GREETING_TITLE] = v }
    suspend fun setUserName(v: String) = put { it[K.USER_NAME] = v }
    suspend fun setAppLocale(v: AppLocale) = put { it[K.APP_LOCALE] = v.storageName }
    suspend fun setDefaultStartPage(v: Int) = put { it[K.DEFAULT_START_PAGE] = v.coerceIn(0, 3) }

    // ---- Playback ----
    suspend fun setKeepPlayingOnClose(v: Boolean) = put { it[K.KEEP_PLAYING_ON_CLOSE] = v }
    suspend fun setRestoreLastPlayedSong(v: Boolean) = put { it[K.RESTORE_LAST_PLAYED_SONG] = v }
    suspend fun setReplayGainMode(v: ReplayGainMode) = put { it[K.REPLAY_GAIN_MODE] = v.storageName }
    suspend fun setPauseOnDuck(v: Boolean) = put { it[K.PAUSE_ON_DUCK] = v }
    suspend fun setPauseOnDisconnect(v: Boolean) = put { it[K.PAUSE_ON_DISCONNECT] = v }
    suspend fun setResumeOnConnect(v: Boolean) = put { it[K.RESUME_ON_CONNECT] = v }
    suspend fun setBluetoothMfxPatch(v: Boolean) = put { it[K.MFX_BLUETOOTH_PATCH] = v }
    suspend fun setSleepTimerDefaultMinutes(v: Int) = put { it[K.SLEEP_TIMER_DEFAULT_MINUTES] = v }
    suspend fun setSleepTimerFadeOutSeconds(v: Int) = put { it[K.SLEEP_TIMER_FADE_OUT_SECONDS] = v }
    suspend fun setSleepTimerFinishSong(v: Boolean) = put { it[K.SLEEP_TIMER_FINISH_SONG] = v }
    suspend fun setRepeatMode(v: RepeatMode) = put { it[K.REPEAT_MODE] = v.storageName }
    suspend fun setVolume(v: Double) = put { it[K.VOLUME] = v }
    suspend fun setSpeed(v: Double) = put { it[K.SPEED] = v }
    suspend fun setMfxPreset(v: MfxPreset?) = put {
        if (v == null) it.remove(K.MFX_PRESET_NAME) else it[K.MFX_PRESET_NAME] = v.storageName
    }
    suspend fun setMfxReverbEnabled(v: Boolean) = put { it[K.MFX_REVERB_ENABLED] = v }
    suspend fun setMfxLofiEnabled(v: Boolean) = put { it[K.MFX_LOFI_ENABLED] = v }
    suspend fun setMfxBassBoostEnabled(v: Boolean) = put { it[K.MFX_BASS_BOOST_ENABLED] = v }
    suspend fun setMfxCustomEqEnabled(v: Boolean) = put { it[K.MFX_CUSTOM_EQ_ENABLED] = v }
    suspend fun setMfxCustomEqGains(v: List<Double>?) = put {
        if (v == null) it.remove(K.MFX_CUSTOM_EQ_GAINS) else it[K.MFX_CUSTOM_EQ_GAINS] = json.encodeToString(v)
    }

    // ---- Library / display ----
    suspend fun setFilterTitleFeatures(v: Boolean) = put { it[K.FILTER_TITLE_FEATURES] = v }
    suspend fun setFilterTitleArtist(v: Boolean) = put { it[K.FILTER_TITLE_ARTIST] = v }
    suspend fun setSongSort(by: SongSort, ascending: Boolean) = put {
        it[K.SONG_SORT_BY] = by.storageName; it[K.SONG_SORT_ASCENDING] = ascending
    }
    suspend fun setAlbumSort(by: AlbumSort, ascending: Boolean) = put {
        it[K.ALBUM_SORT_BY] = by.storageName; it[K.ALBUM_SORT_ASCENDING] = ascending
    }
    suspend fun setArtistSort(by: ArtistSort, ascending: Boolean) = put {
        it[K.ARTIST_SORT_BY] = by.storageName; it[K.ARTIST_SORT_ASCENDING] = ascending
    }
    suspend fun setPlaylistSort(by: PlaylistSort, ascending: Boolean) = put {
        it[K.PLAYLIST_SORT_BY] = by.storageName; it[K.PLAYLIST_SORT_ASCENDING] = ascending
    }
    suspend fun setFavoritesSort(by: FavoritesSort, ascending: Boolean) = put {
        it[K.FAVORITES_SORT_BY] = by.storageName; it[K.FAVORITES_SORT_ASCENDING] = ascending
    }
    suspend fun setSongActivityView(v: SongActivityView) = put { it[K.SONG_ACTIVITY_VIEW] = v.storageName }
    suspend fun addColorlessArtworkPath(path: String) = put {
        it[K.COLORLESS_ARTWORK_PATHS] = (it[K.COLORLESS_ARTWORK_PATHS] ?: emptySet()) + path
    }

    // ---- Sync / onboarding / updates ----
    suspend fun setScanFolder(path: String?, uri: String?) = put {
        if (path == null) it.remove(K.SCAN_FOLDER_PATH) else it[K.SCAN_FOLDER_PATH] = path
        if (uri == null) it.remove(K.SCAN_FOLDER_URI) else it[K.SCAN_FOLDER_URI] = uri
    }
    /** LIB-04: records a successful sync and clears `postpone_sync_until`. */
    suspend fun recordSync(timestampMs: Long, durationMs: Long) = put {
        it[K.LAST_SYNC_TIMESTAMP] = timestampMs
        it[K.LAST_SYNC_DURATION_MS] = durationMs
        it[K.POSTPONE_SYNC_UNTIL] = 0L
    }
    suspend fun setPostponeSyncUntil(ms: Long) = put { it[K.POSTPONE_SYNC_UNTIL] = ms }
    suspend fun setOnboardingCompleted(v: Boolean) = put { it[K.ONBOARDING_COMPLETED] = v }
    suspend fun setAutoCheckUpdates(v: Boolean) = put { it[K.AUTO_CHECK_UPDATES] = v }
    suspend fun setUpdateChannel(v: UpdateChannel) = put { it[K.UPDATE_CHANNEL] = v.storageName; it.remove(K.INCLUDE_PRERELEASES) }
    suspend fun setLastUpdateCheckMs(v: Long) = put { it[K.LAST_UPDATE_CHECK_MS] = v }
    suspend fun setUpdateRateLimitResetMs(v: Long) = put { it[K.UPDATE_RATE_LIMIT_RESET_MS] = v }

    /** Raw access for backup export/restore (BAK-01/04). */
    suspend fun edit(block: (MutablePreferences) -> Unit) = put(block)

    private suspend fun put(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** (use_material_you, use_dynamic_theme) Flutter mirror; Afterglow has no Flutter equivalent → custom. */
        fun legacyMirror(source: ThemeColorSource): Pair<Boolean, Boolean> = when (source) {
            ThemeColorSource.System -> true to false
            ThemeColorSource.AlbumArt -> false to true
            ThemeColorSource.Custom, ThemeColorSource.Afterglow -> false to false
        }

        /** Flutter derivation (audit/settings §9): materialYou → System, else dynamic → AlbumArt, else Custom. */
        fun fromLegacy(useMaterialYou: Boolean, useDynamicTheme: Boolean): ThemeColorSource = when {
            useMaterialYou -> ThemeColorSource.System
            useDynamicTheme -> ThemeColorSource.AlbumArt
            else -> ThemeColorSource.Custom
        }

        fun read(p: Preferences): AppSettings {
            val d = AppSettings()
            val colorSource = p[K.THEME_COLOR_SOURCE]?.let(ThemeColorSource::fromStorage)
                ?: if (p[K.USE_MATERIAL_YOU] != null || p[K.USE_DYNAMIC_THEME] != null) {
                    fromLegacy(p[K.USE_MATERIAL_YOU] ?: false, p[K.USE_DYNAMIC_THEME] ?: true)
                } else d.themeColorSource
            return AppSettings(
                themeMode = ThemeModeSetting.fromStorage(p[K.THEME_MODE]),
                themeColorSource = colorSource,
                useMaterialYou = p[K.USE_MATERIAL_YOU] ?: d.useMaterialYou,
                useDynamicTheme = p[K.USE_DYNAMIC_THEME] ?: d.useDynamicTheme,
                defaultThemeColor = p[K.DEFAULT_THEME_COLOR] ?: d.defaultThemeColor,
                amoledDark = p[K.AMOLED_DARK] ?: d.amoledDark,
                blurredBackgrounds = p[K.BLURRED_BACKGROUNDS] ?: d.blurredBackgrounds,
                nowPlayingStyle = NowPlayingStyle.fromStorage(p[K.NOW_PLAYING_STYLE]),
                preferLocalArtistImages = p[K.PREFER_LOCAL_ARTIST_IMAGES] ?: d.preferLocalArtistImages,
                showVisualizer = p[K.SHOW_VISUALIZER] ?: d.showVisualizer,
                immersiveMode = p[K.IMMERSIVE_MODE] ?: d.immersiveMode,
                useGreetingTitle = p[K.USE_GREETING_TITLE] ?: d.useGreetingTitle,
                userName = p[K.USER_NAME] ?: d.userName,
                appLocale = AppLocale.fromStorage(p[K.APP_LOCALE]),
                defaultStartPage = (p[K.DEFAULT_START_PAGE] ?: d.defaultStartPage).coerceIn(0, 3),
                keepPlayingOnClose = p[K.KEEP_PLAYING_ON_CLOSE] ?: d.keepPlayingOnClose,
                restoreLastPlayedSong = p[K.RESTORE_LAST_PLAYED_SONG] ?: d.restoreLastPlayedSong,
                replayGainMode = ReplayGainMode.fromStorage(p[K.REPLAY_GAIN_MODE]),
                pauseOnDuck = p[K.PAUSE_ON_DUCK] ?: d.pauseOnDuck,
                pauseOnDisconnect = p[K.PAUSE_ON_DISCONNECT] ?: d.pauseOnDisconnect,
                resumeOnConnect = p[K.RESUME_ON_CONNECT] ?: d.resumeOnConnect,
                bluetoothMfxPatch = p[K.MFX_BLUETOOTH_PATCH] ?: d.bluetoothMfxPatch,
                sleepTimerDefaultMinutes = p[K.SLEEP_TIMER_DEFAULT_MINUTES] ?: d.sleepTimerDefaultMinutes,
                sleepTimerFadeOutSeconds = p[K.SLEEP_TIMER_FADE_OUT_SECONDS] ?: d.sleepTimerFadeOutSeconds,
                sleepTimerFinishSong = p[K.SLEEP_TIMER_FINISH_SONG] ?: d.sleepTimerFinishSong,
                repeatMode = RepeatMode.fromStorage(p[K.REPEAT_MODE]),
                filterTitleFeatures = p[K.FILTER_TITLE_FEATURES] ?: d.filterTitleFeatures,
                filterTitleArtist = p[K.FILTER_TITLE_ARTIST] ?: d.filterTitleArtist,
                autoCheckUpdates = p[K.AUTO_CHECK_UPDATES] ?: d.autoCheckUpdates,
                updateChannel = UpdateChannel.fromStored(p[K.UPDATE_CHANNEL], p[K.INCLUDE_PRERELEASES]),
                songSortBy = SongSort.fromStorage(p[K.SONG_SORT_BY]),
                songSortAscending = p[K.SONG_SORT_ASCENDING] ?: d.songSortAscending,
                albumSortBy = AlbumSort.fromStorage(p[K.ALBUM_SORT_BY]),
                albumSortAscending = p[K.ALBUM_SORT_ASCENDING] ?: d.albumSortAscending,
                artistSortBy = ArtistSort.fromStorage(p[K.ARTIST_SORT_BY]),
                artistSortAscending = p[K.ARTIST_SORT_ASCENDING] ?: d.artistSortAscending,
                playlistSortBy = PlaylistSort.fromStorage(p[K.PLAYLIST_SORT_BY]),
                playlistSortAscending = p[K.PLAYLIST_SORT_ASCENDING] ?: d.playlistSortAscending,
                favoritesSortBy = FavoritesSort.fromStorage(p[K.FAVORITES_SORT_BY]),
                favoritesSortAscending = p[K.FAVORITES_SORT_ASCENDING] ?: d.favoritesSortAscending,
                songActivityView = SongActivityView.fromStorage(p[K.SONG_ACTIVITY_VIEW]),
                volume = p[K.VOLUME] ?: d.volume,
                speed = p[K.SPEED] ?: d.speed,
                mfxPreset = MfxPreset.fromStorage(p[K.MFX_PRESET_NAME]),
                mfxReverbEnabled = p[K.MFX_REVERB_ENABLED] ?: d.mfxReverbEnabled,
                mfxLofiEnabled = p[K.MFX_LOFI_ENABLED] ?: d.mfxLofiEnabled,
                mfxBassBoostEnabled = p[K.MFX_BASS_BOOST_ENABLED] ?: d.mfxBassBoostEnabled,
                mfxCustomEqEnabled = p[K.MFX_CUSTOM_EQ_ENABLED] ?: d.mfxCustomEqEnabled,
                mfxCustomEqGains = p[K.MFX_CUSTOM_EQ_GAINS]?.let {
                    runCatching { json.decodeFromString<List<Double>>(it) }.getOrNull()
                },
                scanFolderPath = p[K.SCAN_FOLDER_PATH],
                scanFolderUri = p[K.SCAN_FOLDER_URI],
                lastSyncTimestampMs = p[K.LAST_SYNC_TIMESTAMP],
                lastSyncDurationMs = p[K.LAST_SYNC_DURATION_MS],
                postponeSyncUntilMs = p[K.POSTPONE_SYNC_UNTIL] ?: d.postponeSyncUntilMs,
                onboardingCompleted = p[K.ONBOARDING_COMPLETED] ?: d.onboardingCompleted,
                colorlessArtworkPaths = p[K.COLORLESS_ARTWORK_PATHS] ?: d.colorlessArtworkPaths,
                lastUpdateCheckMs = p[K.LAST_UPDATE_CHECK_MS] ?: d.lastUpdateCheckMs,
                updateRateLimitResetMs = p[K.UPDATE_RATE_LIMIT_RESET_MS] ?: d.updateRateLimitResetMs,
            )
        }
    }
}
