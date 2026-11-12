package de.yurtemre.sonora.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import de.yurtemre.sonora.shared.library.TitleFormatter
import de.yurtemre.sonora.shared.model.Song

/** Display settings read across the library and player, provided once by `MainActivity`. */
@Immutable
data class DisplayPrefs(
    val filterTitleFeatures: Boolean = false,
    val filterTitleArtist: Boolean = false,
    val filterTitleVideoTags: Boolean = false,
    val preferLocalArtistImages: Boolean = true,
)

val LocalDisplayPrefs = compositionLocalOf { DisplayPrefs() }

/** LIB-14 title as shown in lists and the player; search, sort and M3U keep the raw title. */
@Composable
fun Song.displayTitle(): String {
    val p = LocalDisplayPrefs.current
    return TitleFormatter.displayTitle(title, artist, p.filterTitleFeatures, p.filterTitleArtist, p.filterTitleVideoTags)
}

/** Artist art: the folder image (`artist.jpg` …) only when "Prefer local artist images" is on. */
@Composable
fun preferredArtistImage(localImagePath: String?): String? =
    localImagePath.takeIf { LocalDisplayPrefs.current.preferLocalArtistImages }
