package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.ui.theme.ArtworkShape
import de.yurtemre.sonora.ui.theme.SonoraTheme

/** Fallback artwork: MaterialShape (Pentagon/Sunny by title hash) filled with a tonal gradient. */
@Composable
fun PlaceholderArt(title: String, modifier: Modifier = Modifier) {
    val hash = remember(title) { title.hashCode() and 0x7fffffff }
    val cs = MaterialTheme.colorScheme
    val tones = listOf(
        cs.primaryContainer to cs.primary,
        cs.secondaryContainer to cs.secondary,
        cs.tertiaryContainer to cs.tertiary,
    )
    val (a, b) = tones[hash % tones.size]
    val shape = remember(hash) { (if (hash / 3 % 2 == 0) MaterialShapes.Pentagon else MaterialShapes.Sunny) }.toShape()
    Box(
        modifier.clip(ArtworkShape).background(cs.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize(0.72f).clip(shape)
                .background(Brush.linearGradient(listOf(a, lerp(a, b, 0.55f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, null, tint = cs.onPrimaryContainer.copy(alpha = 0.8f), modifier = Modifier.fillMaxSize(0.4f))
        }
    }
}

@Preview
@Composable
private fun PlaceholderArtPreview() = SonoraTheme { PlaceholderArt("Afterglow", Modifier.size(96.dp)) }
