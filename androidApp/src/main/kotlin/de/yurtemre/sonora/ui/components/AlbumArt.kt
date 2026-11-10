package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.theme.ArtworkShape
import de.yurtemre.sonora.ui.theme.SonoraTheme

/** Decode size hints so lists never decode full-resolution covers. */
enum class ArtSize(val px: Int) { Small(128), Medium(512), Large(800) }

@Composable
fun AlbumArt(
    model: Any?,
    title: String,
    modifier: Modifier = Modifier,
    size: ArtSize = ArtSize.Small,
    shape: Shape = ArtworkShape,
    decorative: Boolean = false,
) {
    val context = LocalContext.current
    val desc = if (decorative) null else stringResource(R.string.cd_album_art, title)
    if (model == null) {
        PlaceholderArt(title, modifier.clip(shape))
        return
    }
    val request = remember(model, size) {
        ImageRequest.Builder(context).data(model).size(size.px).crossfade(true).build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = desc,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(shape),
        loading = { PlaceholderArt(title) },
        error = { PlaceholderArt(title) },
    )
}

@Preview
@Composable
private fun AlbumArtPreview() = SonoraTheme { Box { AlbumArt(null, "Midnight", Modifier.size(72.dp)) } }
