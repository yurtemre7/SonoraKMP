package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.ArtSize
import de.yurtemre.sonora.ui.components.displayTitle
import de.yurtemre.sonora.ui.theme.SleeveShape

/** The listener's most played (else first favorite) song: cover, title, artist, play count and a play button. */
@Composable
fun FavoriteSongCard(song: Song, plays: Int, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val title = song.displayTitle()
    val playDesc = stringResource(R.string.home_card_fav_play, title)
    val play = {
        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        onPlay()
    }
    Surface(
        onClick = play,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = SleeveShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArt(song.artworkPath, song.album, Modifier.size(72.dp), size = ArtSize.Medium, decorative = true, artist = song.artist)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_card_fav_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (plays > 0) {
                    Text(
                        stringResource(R.string.home_card_fav_plays, plays),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledIconButton(onClick = play, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.PlayArrow, playDesc, Modifier.size(28.dp))
            }
        }
    }
}
