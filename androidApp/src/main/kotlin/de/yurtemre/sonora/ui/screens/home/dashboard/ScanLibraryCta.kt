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
import de.yurtemre.sonora.ui.screens.home.BackgroundWorkProgress

/**
 * "Scan library" tile. While [isSyncing] it shows a spinner and the shared stage progress bar (as in Settings), and is
 * disabled so a second scan can't be started. Min height 120dp (shared with Shuffle) fits the progress block, so the row never grows.
 */
@Composable
fun ScanLibraryCta(
    isSyncing: Boolean,
    tasks: List<BackgroundWork>,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
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
            Modifier.heightIn(min = 120.dp).padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.size(32.dp)) {
                Crossfade(targetState = isSyncing, label = "scanCtaIcon") { busy ->
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    else Icon(Icons.Rounded.Sync, null, Modifier.size(32.dp), tint = scheme.primary)
                }
            }
            if (isSyncing && tasks.isNotEmpty()) {
                // Same stage caption and wavy bar as the Settings scan card, in the tile's width.
                BackgroundWorkProgress(tasks)
            } else {
                Text(
                    stringResource(if (isSyncing) R.string.home_card_scanning else R.string.home_card_scan),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
