package de.yurtemre.sonora.ui.screens.nowplaying

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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.Lyrics
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.scan.LyricsLocator
import de.yurtemre.sonora.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lyrics shown over the cover (first tap on Lyrics): the cover dimmed under the previous / current / next line of
 * synced lyrics. Fills its parent (the sleeve) and takes no touches, so swiping to skip and tapping for immersive
 * still reach the cover below. Songs without synced lyrics get a one-line note instead. [positionMs] is read lazily
 * so position ticks only recompose when the active line changes.
 */
@Composable
fun ArtLyricsOverlay(song: Song, positionMs: () -> Long, modifier: Modifier = Modifier) {
    // Null while loading; loaded once per song.
    val lyrics by produceState<Lyrics?>(null, song.id, song.filePath) {
        value = null
        value = withContext(Dispatchers.IO) { LyricsLocator.load(song.filePath) }
    }
    val synced = lyrics?.takeIf { it.isSynchronized && it.lines.isNotEmpty() }
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        when {
            synced != null -> LiveLines(synced, positionMs)
            lyrics == null -> Unit
            else -> Text(
                stringResource(if (lyrics!!.lines.isEmpty()) R.string.lyrics_empty_title else R.string.np_lyrics_art_plain),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun LiveLines(lyrics: Lyrics, positionMs: () -> Long) {
    val lines = lyrics.lines
    val reduceMotion = LocalReduceMotion.current
    val active by remember(lines) { derivedStateOf { lines.indexOfLast { it.timeMs <= positionMs() } } }
    Crossfade(
        targetState = active,
        animationSpec = if (reduceMotion) snap() else tween(300),
        label = "artLyrics",
    ) { index ->
        var entered by remember { mutableStateOf(reduceMotion) }
        LaunchedEffect(Unit) { entered = true }
        val scale by animateFloatAsState(
            targetValue = if (entered) 1f else 0.94f,
            animationSpec = if (reduceMotion) snap() else tween(300),
            label = "artLyricScale",
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).semantics { liveRegion = LiveRegionMode.Polite },
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
                style = MaterialTheme.typography.headlineSmall,
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
