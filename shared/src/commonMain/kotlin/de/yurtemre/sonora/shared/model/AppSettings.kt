package de.yurtemre.sonora.shared.model

/**
 * Every DataStore setting of SPEC §5.3 with its default (audit/settings §1).
 * Playback-state keys (`last_played_*`) live in `PlaybackStateStore`, not here.
 */
data class AppSettings(
    val themeMode: ThemeModeSetting = ThemeModeSetting.System,
    val themeColorSource: ThemeColorSource = ThemeColorSource.Afterglow,
    val useMaterialYou: Boolean = false,
    val useDynamicTheme: Boolean = true,
    val defaultThemeColor: Long = 0xFFE5573BL,
    val amoledDark: Boolean = false,
    val blurredBackgrounds: Boolean = false,
    val nowPlayingStyle: NowPlayingStyle = NowPlayingStyle.Modern,
    val preferLocalArtistImages: Boolean = true,
    val showVisualizer: Boolean = false,
    val immersiveMode: Boolean = false,
    val useGreetingTitle: Boolean = false,
    val userName: String = "User",
    val appLocale: AppLocale = AppLocale.System,
    /** 0..3 */
    val defaultStartPage: Int = 0,
    val keepPlayingOnClose: Boolean = false,
    val restoreLastPlayedSong: Boolean = true,
    val replayGainMode: ReplayGainMode = ReplayGainMode.Off,
    val pauseOnDuck: Boolean = false,
    val pauseOnDisconnect: Boolean = true,
    val resumeOnConnect: Boolean = false,
    val bluetoothMfxPatch: Boolean = false,
    val sleepTimerDefaultMinutes: Int = 5,
    val sleepTimerFadeOutSeconds: Int = 10,
    val sleepTimerFinishSong: Boolean = false,
    val filterTitleFeatures: Boolean = false,
    val filterTitleArtist: Boolean = false,
    val autoCheckUpdates: Boolean = true,
    val includePrereleases: Boolean = false,
    val songSortBy: SongSort = SongSort.Title,
    val songSortAscending: Boolean = true,
    val albumSortBy: AlbumSort = AlbumSort.Name,
    val albumSortAscending: Boolean = true,
    val artistSortBy: ArtistSort = ArtistSort.Name,
    val artistSortAscending: Boolean = true,
    val playlistSortBy: PlaylistSort = PlaylistSort.Name,
    val playlistSortAscending: Boolean = true,
    val favoritesSortBy: FavoritesSort = FavoritesSort.Date,
    val favoritesSortAscending: Boolean = false,
    val songActivityView: SongActivityView = SongActivityView.All,
    val volume: Double = 1.0,
    val speed: Double = 1.0,
    val mfxPreset: MfxPreset? = null,
    val mfxReverbEnabled: Boolean = false,
    val mfxLofiEnabled: Boolean = false,
    val mfxBassBoostEnabled: Boolean = false,
    val mfxCustomEqEnabled: Boolean = false,
    /** null = unset (`mfx_customEqGains`). */
    val mfxCustomEqGains: List<Double>? = null,
    val scanFolderPath: String? = null,
    val scanFolderUri: String? = null,
    val lastSyncTimestampMs: Long? = null,
    val lastSyncDurationMs: Long? = null,
    val postponeSyncUntilMs: Long = 0,
    val onboardingCompleted: Boolean = false,
    val colorlessArtworkPaths: Set<String> = emptySet(),
    val lastUpdateCheckMs: Long = 0,
    val updateRateLimitResetMs: Long = 0,
) {
    companion object {
        val SLEEP_TIMER_MINUTES_OPTIONS = listOf(5, 10, 15, 20, 25, 30, 45, 60)
        val SLEEP_TIMER_FADE_OPTIONS = listOf(0, 10, 30, 60)
    }
}
