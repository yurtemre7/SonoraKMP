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

/**
 * How the song title is shown on Now Playing, in every [NowPlayingStyle] (KMP-only key `title_style`, not in backups).
 * The first four keep the Spine (title along the sleeve) and differ in how a title that is too long is handled.
 */
enum class TitleStyle(override val storageName: String) : StorageEnum {
    /** Spine; scrolls once to the end when the title is cut, then rests at the start. */
    Marquee("marquee"),
    /** Spine; the type shrinks until the whole title fits (down to a floor, then it is clipped). */
    Fit("fit"),
    /** Spine; a long title wraps into a second column along the sleeve. */
    TwoColumns("two_columns"),
    /** Spine as before; when the title is cut the full title also shows in small type under the cover. */
    Hybrid("hybrid"),
    /** A plain title under the artwork. */
    Classic("classic");

    companion object {
        fun fromStorage(value: String?): TitleStyle = enumFromStorage(value, Hybrid)
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
    System("system"), English("en"), Japanese("ja"), Turkish("tr");

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
