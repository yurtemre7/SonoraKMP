package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
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
import de.yurtemre.sonora.playback.api.SleepTimerUiState
import de.yurtemre.sonora.ui.format.DurationFormat

/** Compact pill showing an active sleep timer's remaining countdown or finish-song mode. */
@Composable
fun SleepTimerBadge(
    state: SleepTimerUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (state) {
        SleepTimerUiState.Inactive -> return
        is SleepTimerUiState.Countdown -> DurationFormat.clockMs(state.remainingMs)
        is SleepTimerUiState.FinishSong -> stringResource(R.string.sleep_badge_finish)
    }
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = cs.primaryContainer,
        contentColor = cs.onPrimaryContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Bedtime,
                contentDescription = stringResource(R.string.np_sleep_timer),
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}
