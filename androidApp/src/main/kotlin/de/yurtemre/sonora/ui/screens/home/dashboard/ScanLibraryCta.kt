package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.repo.api.BackgroundWork

/**
 * "Scan library" tile. While [isSyncing] it shows a progress ring (determinate once the Scanning stage reports a
 * total) with a counter, and is disabled so a second scan can't be started.
 */
@Composable
fun ScanLibraryCta(
    isSyncing: Boolean,
    tasks: List<BackgroundWork>,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scanning = tasks.firstOrNull { it.stage == BackgroundWork.Stage.Scanning }
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            onScan()
        },
        enabled = !isSyncing,
        modifier = modifier,
        shape = HomeCtaShape,
        color = if (isSyncing) scheme.secondaryContainer else scheme.surfaceContainerHigh,
        contentColor = if (isSyncing) scheme.onSecondaryContainer else scheme.onSurface,
    ) {
        Column(
            Modifier.heightIn(min = 104.dp).padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.size(32.dp)) {
                Crossfade(targetState = isSyncing, label = "scanCtaIcon") { busy ->
                    if (busy) {
                        if (scanning != null && !scanning.isIndeterminate) {
                            val p = (scanning.done.toFloat() / scanning.total).coerceIn(0f, 1f)
                            CircularProgressIndicator(progress = { p }, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        }
                    } else {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(32.dp), tint = scheme.primary)
                    }
                }
            }
            Column {
                Text(
                    stringResource(if (isSyncing) R.string.home_card_scanning else R.string.home_card_scan),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isSyncing && scanning != null && !scanning.isIndeterminate) {
                    Text(
                        stringResource(R.string.home_card_scan_progress, scanning.done, scanning.total),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
