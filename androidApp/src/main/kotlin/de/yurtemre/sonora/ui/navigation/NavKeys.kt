package de.yurtemre.sonora.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Library tabs; index matches the `default_start_page` setting (0..3). */
@Serializable
enum class LibraryTab { Songs, Albums, Artists, Playlists;
    companion object {
        fun fromStartPage(index: Int): LibraryTab = entries.getOrElse(index) { Songs }
    }
}

/** Navigation 3 destinations (SPEC §6). Now Playing is an overlay, not a key; the gate is outside the back stack. */
@Serializable data class Library(val tab: LibraryTab) : NavKey
@Serializable data class Album(val name: String, val artist: String) : NavKey
@Serializable data class Artist(val name: String) : NavKey
@Serializable data class Playlist(val id: String) : NavKey
@Serializable data object Favorites : NavKey
@Serializable data object RecentlyPlayed : NavKey
@Serializable data object Stats : NavKey
@Serializable data object Settings : NavKey
@Serializable data object SettingsAppearance : NavKey
@Serializable data object SettingsFormatting : NavKey
@Serializable data object SettingsPlayback : NavKey
@Serializable data object SettingsPrivacy : NavKey
@Serializable data object SettingsBackup : NavKey
@Serializable data object SettingsInfo : NavKey
@Serializable data object Changelog : NavKey
@Serializable data object Licenses : NavKey
@Serializable data object DebugCaches : NavKey
