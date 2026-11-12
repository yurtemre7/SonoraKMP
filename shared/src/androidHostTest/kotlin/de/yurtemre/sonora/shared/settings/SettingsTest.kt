package de.yurtemre.sonora.shared.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import de.yurtemre.sonora.shared.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {
    private fun newRepo() = SettingsRepository(PreferenceDataStoreFactory.createWithPath {
        java.io.File.createTempFile("settings", ".preferences_pb").also { it.delete() }.toOkioPath()
    })

    @Test fun defaultsAndEverySetterRoundTrip() = runBlocking {
        val repo = newRepo()
        val fresh = repo.settings.first()
        // SPEC §5.3 defaults (spot-checked literally, then the full data-class default).
        assertEquals(ThemeColorSource.Afterglow, fresh.themeColorSource)
        assertEquals(0xFFE5573BL, fresh.defaultThemeColor)
        assertEquals("User", fresh.userName)
        assertEquals(5, fresh.sleepTimerDefaultMinutes)
        assertEquals(10, fresh.sleepTimerFadeOutSeconds)
        assertEquals(UpdateChannel.Stable, fresh.updateChannel)
        assertEquals(FavoritesSort.Date, fresh.favoritesSortBy)
        assertEquals(false, fresh.favoritesSortAscending)
        assertEquals(AppSettings(), fresh)

        suspend fun <T> check(name: String, value: T, set: suspend (T) -> Unit, get: (AppSettings) -> T) {
            set(value)
            assertEquals(value, get(repo.settings.first()), name)
        }
        with(repo) {
            check("themeMode", ThemeModeSetting.Dark, ::setThemeMode) { it.themeMode }
            check("defaultThemeColor", 0xFF112233L, ::setDefaultThemeColor) { it.defaultThemeColor }
            check("amoledDark", true, ::setAmoledDark) { it.amoledDark }
            check("blurredBackgrounds", true, ::setBlurredBackgrounds) { it.blurredBackgrounds }
            check("nowPlayingStyle", NowPlayingStyle.entries.last(), ::setNowPlayingStyle) { it.nowPlayingStyle }
            check("preferLocalArtistImages", false, ::setPreferLocalArtistImages) { it.preferLocalArtistImages }
            check("showVisualizer", true, ::setShowVisualizer) { it.showVisualizer }
            check("immersiveMode", true, ::setImmersiveMode) { it.immersiveMode }
            check("useGreetingTitle", true, ::setUseGreetingTitle) { it.useGreetingTitle }
            check("userName", "Emi", ::setUserName) { it.userName }
            check("appLocale", AppLocale.entries.last(), ::setAppLocale) { it.appLocale }
            check("defaultStartPage", 3, ::setDefaultStartPage) { it.defaultStartPage }
            setDefaultStartPage(9); assertEquals(3, settings.first().defaultStartPage, "startPage clamp")
            check("keepPlayingOnClose", true, ::setKeepPlayingOnClose) { it.keepPlayingOnClose }
            check("restoreLastPlayedSong", false, ::setRestoreLastPlayedSong) { it.restoreLastPlayedSong }
            check("replayGainMode", ReplayGainMode.entries.last(), ::setReplayGainMode) { it.replayGainMode }
            check("pauseOnDuck", true, ::setPauseOnDuck) { it.pauseOnDuck }
            check("pauseOnDisconnect", false, ::setPauseOnDisconnect) { it.pauseOnDisconnect }
            check("resumeOnConnect", true, ::setResumeOnConnect) { it.resumeOnConnect }
            check("bluetoothMfxPatch", true, ::setBluetoothMfxPatch) { it.bluetoothMfxPatch }
            check("sleepTimerDefaultMinutes", 45, ::setSleepTimerDefaultMinutes) { it.sleepTimerDefaultMinutes }
            check("sleepTimerFadeOutSeconds", 60, ::setSleepTimerFadeOutSeconds) { it.sleepTimerFadeOutSeconds }
            check("sleepTimerFinishSong", true, ::setSleepTimerFinishSong) { it.sleepTimerFinishSong }
            check("repeatMode", RepeatMode.entries.last(), ::setRepeatMode) { it.repeatMode }
            check("volume", 0.4, ::setVolume) { it.volume }
            check("speed", 1.25, ::setSpeed) { it.speed }
            check("mfxPreset", MfxPreset.entries.last(), ::setMfxPreset) { it.mfxPreset }
            check("mfxPreset=null", null, ::setMfxPreset) { it.mfxPreset }
            check("mfxReverb", true, ::setMfxReverbEnabled) { it.mfxReverbEnabled }
            check("mfxLofi", true, ::setMfxLofiEnabled) { it.mfxLofiEnabled }
            check("mfxBass", true, ::setMfxBassBoostEnabled) { it.mfxBassBoostEnabled }
            check("mfxCustomEq", true, ::setMfxCustomEqEnabled) { it.mfxCustomEqEnabled }
            check("mfxGains", listOf(1.5, -2.0, 0.0), ::setMfxCustomEqGains) { it.mfxCustomEqGains }
            check("mfxGains=null", null, ::setMfxCustomEqGains) { it.mfxCustomEqGains }
            check("filterTitleFeatures", true, ::setFilterTitleFeatures) { it.filterTitleFeatures }
            check("filterTitleArtist", true, ::setFilterTitleArtist) { it.filterTitleArtist }
            check("songSort", SongSort.entries.last() to false, { setSongSort(it.first, it.second) }) { it.songSortBy to it.songSortAscending }
            check("albumSort", AlbumSort.entries.last() to false, { setAlbumSort(it.first, it.second) }) { it.albumSortBy to it.albumSortAscending }
            check("artistSort", ArtistSort.entries.last() to false, { setArtistSort(it.first, it.second) }) { it.artistSortBy to it.artistSortAscending }
            check("playlistSort", PlaylistSort.entries.last() to false, { setPlaylistSort(it.first, it.second) }) { it.playlistSortBy to it.playlistSortAscending }
            check("favoritesSort", FavoritesSort.entries.first() to true, { setFavoritesSort(it.first, it.second) }) { it.favoritesSortBy to it.favoritesSortAscending }
            check("songActivityView", SongActivityView.entries.last(), ::setSongActivityView) { it.songActivityView }
            addColorlessArtworkPath("/a"); addColorlessArtworkPath("/b")
            assertEquals(setOf("/a", "/b"), settings.first().colorlessArtworkPaths)
            check("scanFolder", "/music" to "content://x", { setScanFolder(it.first, it.second) }) { it.scanFolderPath to it.scanFolderUri }
            check("postponeSync", 777L, ::setPostponeSyncUntil) { it.postponeSyncUntilMs }
            recordSync(1_000L, 50L)
            settings.first().let { assertEquals(Triple(1_000L, 50L, 0L), Triple(it.lastSyncTimestampMs, it.lastSyncDurationMs, it.postponeSyncUntilMs)) }
            check("onboarding", true, ::setOnboardingCompleted) { it.onboardingCompleted }
            check("autoCheckUpdates", false, ::setAutoCheckUpdates) { it.autoCheckUpdates }
            check("updateChannel", UpdateChannel.Nightly, ::setUpdateChannel) { it.updateChannel }
            check("lastUpdateCheck", 123L, ::setLastUpdateCheckMs) { it.lastUpdateCheckMs }
            check("rateLimitReset", 456L, ::setUpdateRateLimitResetMs) { it.updateRateLimitResetMs }

            // Colour source writes the Flutter legacy mirrors (use_material_you, use_dynamic_theme).
            for (src in ThemeColorSource.entries) {
                setThemeColorSource(src)
                val s = settings.first()
                assertEquals(src, s.themeColorSource, "colorSource $src")
                assertEquals(SettingsRepository.legacyMirror(src), s.useMaterialYou to s.useDynamicTheme, "mirror $src")
            }
        }
        // Legacy-only store (Flutter restore): source derived from the mirrors.
        val legacy = newRepo()
        legacy.edit { it[SettingsKeys.USE_MATERIAL_YOU] = false; it[SettingsKeys.USE_DYNAMIC_THEME] = true }
        assertEquals(ThemeColorSource.AlbumArt, legacy.settings.first().themeColorSource)
        legacy.edit { it[SettingsKeys.USE_MATERIAL_YOU] = true }
        assertEquals(ThemeColorSource.System, legacy.settings.first().themeColorSource)
    }
}
