package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.MfxUiState
import de.yurtemre.sonora.ui.format.currentLocale

/**
 * Compact pill shown while sound effects are active (same style as [SleepTimerBadge]): the speed when it is
 * not 1× (e.g. "0.85×" slowed, "1.25×" sped up), otherwise just the icon (pitch or EQ only). Tap opens the sheet.
 */
@Composable
fun MfxBadge(
    mfx: MfxUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!mfx.isActive) return
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = cs.tertiaryContainer,
        contentColor = cs.onTertiaryContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = stringResource(R.string.np_mfx_active),
                modifier = Modifier.size(14.dp),
            )
            if (mfx.isSpeedChanged) {
                Text(
                    text = stringResource(R.string.np_mfx_speed, "%.2f".format(currentLocale(), mfx.effectiveSpeed)),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}
