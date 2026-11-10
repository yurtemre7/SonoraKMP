package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.BuildConfig
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.theme.LocalAfterglowExtras
import de.yurtemre.sonora.ui.theme.PillShape
import de.yurtemre.sonora.ui.theme.SonoraTheme

/** Small pill shown only in debug builds. */
@Composable
fun DevBadge(modifier: Modifier = Modifier, show: Boolean = BuildConfig.DEBUG) {
    if (!show) return
    Surface(modifier, shape = PillShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            stringResource(R.string.dev_badge),
            style = LocalAfterglowExtras.current.eyebrow,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Preview
@Composable
private fun DevBadgePreview() = SonoraTheme { DevBadge(show = true) }
