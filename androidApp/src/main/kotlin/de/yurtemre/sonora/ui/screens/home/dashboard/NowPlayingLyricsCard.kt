package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.Lyrics
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.scan.LyricsLocator
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.ArtSize
import de.yurtemre.sonora.ui.components.displayTitle
import de.yurtemre.sonora.ui.theme.LocalReduceMotion
import de.yurtemre.sonora.ui.theme.SleeveShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Now-playing card for Home: the cover, dimmed under live synced lyrics (previous / current / next line) when the
 * song has synced lyrics; otherwise the undimmed cover with a title/artist overlay. [positionMs] is read lazily
 * (inside derivedStateOf) so position ticks only recompose when the active line changes.
 */
@Composable
fun NowPlayingLyricsCard(song: Song, positionMs: () -> Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val title = song.displayTitle()
    val desc = stringResource(R.string.home_np_cd, title, song.artist)
    // Null while loading or when there are no lyrics; loaded once per song.
    val lyrics by produceState<Lyrics?>(null, song.id, song.filePath) {
        value = null
        value = withContext(Dispatchers.IO) { LyricsLocator.load(song.filePath) }
    }
    val synced = lyrics?.takeIf { it.isSynchronized && it.lines.isNotEmpty() }

    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = desc },
        shape = SleeveShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.15f)) {
            AlbumArt(
                song.artworkPath, song.album, Modifier.fillMaxSize(),
                size = ArtSize.Large, decorative = true, artist = song.artist,
            )
            if (synced != null) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                Box(Modifier.fillMaxSize().clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                    LiveLines(synced, positionMs)
                }
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f)),
                    ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp).clearAndSetSemantics { }) {
                    Text(
                        stringResource(R.string.home_np_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveLines(lyrics: Lyrics, positionMs: () -> Long) {
    val lines = lyrics.lines
    val reduceMotion = LocalReduceMotion.current
    // Only changes (and recomposes) when the active line changes, not on every position tick.
    val active by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= positionMs() } }
    }
    Crossfade(
        targetState = active,
        animationSpec = if (reduceMotion) snap() else tween(300),
        label = "npLyrics",
    ) { index ->
        var entered by remember { mutableStateOf(reduceMotion) }
        LaunchedEffect(Unit) { entered = true }
        val scale by animateFloatAsState(
            targetValue = if (entered) 1f else 0.94f,
            animationSpec = if (reduceMotion) snap() else tween(300),
            label = "npLyricScale",
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                lines.getOrNull(index - 1)?.text.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (index >= 0) lines[index].text.ifBlank { "♪" } else "♪",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
            )
            Text(
                lines.getOrNull(index + 1)?.text.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
