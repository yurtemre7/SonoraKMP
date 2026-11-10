package de.yurtemre.sonora.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.graphics.vector.ImageVector
import de.yurtemre.sonora.R

@get:StringRes
val LibraryTab.labelRes: Int
    get() = when (this) {
        LibraryTab.Songs -> R.string.nav_tab_songs
        LibraryTab.Albums -> R.string.nav_tab_albums
        LibraryTab.Artists -> R.string.nav_tab_artists
        LibraryTab.Playlists -> R.string.nav_tab_playlists
    }

val LibraryTab.icon: ImageVector
    get() = when (this) {
        LibraryTab.Songs -> Icons.Rounded.MusicNote
        LibraryTab.Albums -> Icons.Rounded.Album
        LibraryTab.Artists -> Icons.Rounded.Person
        LibraryTab.Playlists -> Icons.Rounded.LibraryMusic
    }
