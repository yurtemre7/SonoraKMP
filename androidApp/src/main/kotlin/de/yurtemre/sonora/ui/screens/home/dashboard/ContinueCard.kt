package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.displayTitle
import de.yurtemre.sonora.ui.theme.SleeveShape

/**
 * "Continue listening": the loaded (paused) song with its progress; tapping resumes it. [progress] is read lazily
 * so position ticks only invalidate the bar.
 */
@Composable
fun ContinueCard(song: Song, progress: () -> Float, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val title = song.displayTitle()
    val desc = stringResource(R.string.home_continue_play, title)
    Surface(
        onClick = onPlay,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = desc },
        shape = SleeveShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(song.artworkPath, song.album, Modifier.size(56.dp), decorative = true, artist = song.artist)
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.home_continue_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(32.dp))
            }
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        }
    }
}
