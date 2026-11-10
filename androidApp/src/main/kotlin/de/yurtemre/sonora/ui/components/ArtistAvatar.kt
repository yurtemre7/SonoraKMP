package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.theme.SonoraTheme
import de.yurtemre.sonora.ui.theme.sleeveStyle

/** Cookie9Sided avatar; without an image it is a flat fill with the artist's initial (DESIGN §3). */
@Composable
fun ArtistAvatar(
    model: Any?,
    name: String,
    modifier: Modifier = Modifier,
    size: ArtSize = ArtSize.Small,
) {
    val desc = stringResource(R.string.cd_artist_avatar, name)
    val shape = MaterialShapes.Cookie9Sided.toShape()
    if (model == null) {
        val fill = remember(name) { sleeveColorFor(name) }
        val initial = remember(name) { name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?" }
        BoxWithConstraints(
            modifier.semantics { contentDescription = desc }.clip(shape).background(fill),
            contentAlignment = Alignment.Center,
        ) {
            val sp = with(LocalDensity.current) { (minOf(maxWidth, maxHeight) * 0.42f).toSp().value.toInt().coerceIn(12, 96) }
            Text(initial, color = Color.White, style = sleeveStyle(sp, sp, 800, 75f))
        }
        return
    }
    AlbumArt(
        model = model,
        title = name,
        modifier = modifier.semantics { contentDescription = desc },
        size = size,
        shape = shape,
        decorative = true,
    )
}

@Preview
@Composable
private fun ArtistAvatarPreview() = SonoraTheme { ArtistAvatar(null, "Aurora", Modifier.size(96.dp)) }

