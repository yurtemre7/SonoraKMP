package de.yurtemre.sonora.shared.model

/** Stats activity view on the songs tab (audit/data §1, `song_activity_view`). */
enum class SongActivityView(override val storageName: String) : StorageEnum {
    All("all"), RecentlyPlayed("recentlyPlayed"), MostPlayed("mostPlayed"), LeastPlayed("leastPlayed");

    companion object {
        fun fromStorage(value: String?): SongActivityView = enumFromStorage(value, All)
    }
}

/** Repeat mode (audit/playback). */
enum class RepeatMode(override val storageName: String) : StorageEnum {
    Off("off"), All("all"), One("one");

    companion object {
        fun fromStorage(value: String?): RepeatMode = enumFromStorage(value, Off)
    }
}

/** Now-playing layout (audit/settings §1, `now_playing_style`). */
enum class NowPlayingStyle(override val storageName: String) : StorageEnum {
    Modern("modern"), Vinyl("vinyl"), Minimalist("minimalist");

    companion object {
        fun fromStorage(value: String?): NowPlayingStyle = enumFromStorage(value, Modern)
    }
}

/** Theme color source (SPEC §5.3 new key `theme_color_source`; legacy mirrors in audit/settings §9). */
enum class ThemeColorSource(override val storageName: String) : StorageEnum {
    Afterglow("afterglow"), System("system"), AlbumArt("album_art"), Custom("custom");

    companion object {
        fun fromStorage(value: String?): ThemeColorSource = enumFromStorage(value, Afterglow)
    }
}

/** Theme brightness mode (`theme_mode`). */
enum class ThemeModeSetting(override val storageName: String) : StorageEnum {
    System("system"), Light("light"), Dark("dark");

    companion object {
        fun fromStorage(value: String?): ThemeModeSetting = enumFromStorage(value, System)
    }
}

/** App language (`app_locale`). */
enum class AppLocale(override val storageName: String) : StorageEnum {
    System("system"), English("en"), Japanese("ja");

    companion object {
        fun fromStorage(value: String?): AppLocale = enumFromStorage(value, System)
    }
}

/** Songs tab sort (audit/ui sort table). */
enum class SongSort(override val storageName: String) : StorageEnum {
    Title("title"), Artist("artist"), Duration("duration"), Recent("recent"), Plays("plays");

    companion object {
        fun fromStorage(value: String?): SongSort = enumFromStorage(value, Title)
    }
}

enum class AlbumSort(override val storageName: String) : StorageEnum {
    Name("name"), Artist("artist"), Tracks("tracks"), Recent("recent");

    companion object {
        fun fromStorage(value: String?): AlbumSort = enumFromStorage(value, Name)
    }
}

enum class ArtistSort(override val storageName: String) : StorageEnum {
    Name("name"), Albums("albums"), Songs("songs");

    companion object {
        fun fromStorage(value: String?): ArtistSort = enumFromStorage(value, Name)
    }
}

enum class PlaylistSort(override val storageName: String) : StorageEnum {
    Name("name"), Songs("songs");

    companion object {
        fun fromStorage(value: String?): PlaylistSort = enumFromStorage(value, Name)
    }
}

enum class FavoritesSort(override val storageName: String) : StorageEnum {
    Name("name"), Duration("duration"), Date("date");

    companion object {
        fun fromStorage(value: String?): FavoritesSort = enumFromStorage(value, Date)
    }
}
