package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.ui.theme.SleeveColors
import de.yurtemre.sonora.ui.theme.SleeveShape
import de.yurtemre.sonora.ui.theme.SonoraTheme
import de.yurtemre.sonora.ui.theme.sleeveStyle

private val SleeveFills = listOf(SleeveColors.Cobalt, SleeveColors.Ink, SleeveColors.Moss)

/** Flat sleeve colour picked by title hash (Cobalt / Ink / Moss). */
fun sleeveColorFor(title: String): Color = SleeveFills[(title.hashCode() and 0x7fffffff) % SleeveFills.size]

/**
 * Typographic sleeve for missing artwork (DESIGN §3): a flat block, album title bottom-left in
 * condensed 800, artist above it, 12% inset. Text is hidden on thumbnails too small to read.
 */
@Composable
fun PlaceholderArt(title: String, modifier: Modifier = Modifier, artist: String? = null) {
    val fill = remember(title) { sleeveColorFor(title) }
    BoxWithConstraints(modifier.clip(SleeveShape).background(fill)) {
        val side = minOf(maxWidth, maxHeight)
        if (side < 56.dp) return@BoxWithConstraints
        val density = LocalDensity.current
        val titleSp = with(density) { (side * 0.14f).toSp().value.toInt().coerceIn(10, 64) }
        val artistSp = (titleSp * 0.5f).toInt().coerceIn(9, 20)
        Column(
            Modifier.fillMaxSize().padding(side * 0.12f),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (!artist.isNullOrBlank()) {
                Text(
                    artist, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = sleeveStyle(artistSp, (artistSp * 1.3f).toInt(), 400),
                )
            }
            Text(
                title, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = sleeveStyle(titleSp, (titleSp * 1.05f).toInt(), 800, 75f),
            )
        }
    }
}

@Preview
@Composable
private fun PlaceholderArtPreview() = SonoraTheme { PlaceholderArt("Night drive", Modifier.size(160.dp), "Aurora") }
