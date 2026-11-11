package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.ArtSize
import de.yurtemre.sonora.ui.theme.SleeveShape

/** Square sleeve (DESIGN §3, 8 dp); tap toggles immersive mode. */
@Composable
fun ArtworkArea(song: Song, immersive: Boolean, onToggleImmersive: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(if (immersive) R.string.np_immersive_exit else R.string.np_immersive_enter)
    AlbumArt(
        model = song.artworkPath,
        title = song.album,
        artist = song.artist,
        size = ArtSize.Large,
        shape = SleeveShape,
        modifier = modifier.fillMaxWidth().aspectRatio(1f).clickable(onClickLabel = label, onClick = onToggleImmersive),
    )
}
