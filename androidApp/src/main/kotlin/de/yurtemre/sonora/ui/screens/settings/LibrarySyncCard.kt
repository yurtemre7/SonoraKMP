package de.yurtemre.sonora.ui.screens.settings

import de.yurtemre.sonora.ui.format.relativeTimeText
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.format.FolderDisplay

/** SET-01 library card: folder ("Music › X"), change folder (SAF), sync now, last sync. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibrarySyncCard(
    folderPath: String?,
    lastSyncMs: Long?,
    songCount: Int,
    syncing: Boolean,
    onChangeFolder: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.small, color = cs.primary, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Folder, null, tint = cs.onPrimary, modifier = Modifier.padding(8.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        folderPath?.let { FolderDisplay.format(context, it) } ?: stringResource(R.string.settings_library_no_folder),
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface,
                    )
                    Text(
                        pluralStringResourceSafe(songCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            val last = lastSyncMs?.let { relativeTimeText(it) } ?: stringResource(R.string.never_synced)
            Text(
                stringResource(R.string.settings_library_last_sync, last),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChangeFolder, enabled = !syncing, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (folderPath == null) R.string.settings_library_choose_folder else R.string.settings_library_change_folder))
                }
                Button(
                    onClick = onSyncNow,
                    enabled = !syncing && folderPath != null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    AnimatedContent(syncing, label = "sync") { busy ->
                        if (busy) LoadingIndicator(Modifier.size(20.dp), color = cs.onPrimary)
                        else Icon(Icons.Rounded.Sync, null, Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (syncing) R.string.settings_library_syncing else R.string.settings_library_sync_now))
                }
            }
        }
    }
}

@Composable
private fun pluralStringResourceSafe(count: Int): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.settings_library_song_count, count, count)
