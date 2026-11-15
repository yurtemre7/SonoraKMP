package de.yurtemre.sonora.ui.screens.devicesync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Rounded container like the library card (SET-01): large shape, surfaceContainerLow, 16 dp padding. */
@Composable
internal fun DeviceSyncCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val outer = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
    if (onClick != null) Surface(onClick = onClick, modifier = outer, shape = shape, color = color, content = inner)
    else Surface(outer, shape = shape, color = color, content = inner)
}

/** Big choice card with an icon, title and body (start screen). */
@Composable
internal fun ChoiceCard(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    DeviceSyncCard(Modifier.heightIn(min = 96.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.medium, color = cs.primaryContainer, modifier = Modifier.size(48.dp)) {
                Icon(icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.padding(12.dp))
            }
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** Wavy progress bar (indeterminate when [progress] is null) with a polite live-region status text. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusProgress(text: String, progress: Float?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val bar = Modifier.fillMaxWidth()
        if (progress != null) {
            LinearWavyProgressIndicator(progress = { progress }, trackColor = cs.surfaceContainerHighest, modifier = bar)
        } else {
            LinearWavyProgressIndicator(trackColor = cs.surfaceContainerHighest, modifier = bar)
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** Error-colored note (failures, wrong code hints). */
@Composable
internal fun ErrorNote(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    DeviceSyncCard(modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = cs.errorContainer) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer)
    }
}

/** Full-width primary/secondary buttons at the end of a screen. */
@Composable
internal fun ActionsRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
