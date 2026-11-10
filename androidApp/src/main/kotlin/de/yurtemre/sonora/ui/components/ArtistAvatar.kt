package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.theme.SonoraTheme

@Composable
fun ArtistAvatar(
    model: Any?,
    name: String,
    modifier: Modifier = Modifier,
    size: ArtSize = ArtSize.Small,
) {
    val desc = stringResource(R.string.cd_artist_avatar, name)
    AlbumArt(
        model = model,
        title = name,
        modifier = modifier.semantics { contentDescription = desc },
        size = size,
        shape = MaterialShapes.Cookie9Sided.toShape(),
        decorative = true,
    )
}

@Preview
@Composable
private fun ArtistAvatarPreview() = SonoraTheme { ArtistAvatar(null, "Aurora", Modifier.size(96.dp)) }
