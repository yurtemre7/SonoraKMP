package de.yurtemre.sonora.ui.screens.home.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.format.countdownText
import de.yurtemre.sonora.ui.screens.weekly.WeeklyMixUi
import de.yurtemre.sonora.ui.theme.SleeveRowShape

/** Entry to the weekly mix above the smart-list chips: cover collage, song count and the refresh countdown. */
@Composable
fun WeeklyMixCard(ui: WeeklyMixUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val covers = ui.songs.distinctBy { it.artworkPath ?: it.album }.take(4)
    val title = stringResource(R.string.weekly_mix_title)
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = SleeveRowShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Collage(covers.map { it.artworkPath to it.album })
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        !ui.loaded -> ""
                        ui.songs.isEmpty() -> stringResource(R.string.weekly_mix_empty)
                        else -> pluralStringResource(R.plurals.song_count, ui.songs.size, ui.songs.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.songs.isNotEmpty()) Text(
                    countdownText(LocalContext.current, ui.countdown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Up to four covers as a flat 2x2 grid (a single cover fills the tile). */
@Composable
private fun Collage(covers: List<Pair<String?, String>>) {
    val tile = 72.dp
    if (covers.size < 4) {
        AlbumArt(covers.firstOrNull()?.first, covers.firstOrNull()?.second ?: "", Modifier.size(tile), shape = SleeveRowShape, decorative = true)
        return
    }
    Column(Modifier.size(tile)) {
        covers.chunked(2).forEach { row ->
            Row(Modifier.weight(1f)) {
                row.forEach { (art, album) ->
                    AlbumArt(art, album, Modifier.weight(1f).fillMaxHeight(), shape = SleeveRowShape, decorative = true)
                }
            }
        }
    }
}
