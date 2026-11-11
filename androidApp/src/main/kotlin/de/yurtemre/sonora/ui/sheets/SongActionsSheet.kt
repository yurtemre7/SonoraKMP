package de.yurtemre.sonora.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.theme.SleeveShape

/** Extra, screen-specific entry for [SongActionsSheet] (e.g. "Remove from playlist"). */
data class SongSheetAction(val label: String, val icon: ImageVector, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * Long-press menu for one song. Quick actions (play next / queue / favourite) sit in a connected button
 * group; the rest are list rows. Each callback is invoked after the sheet asks to close via [onDismiss],
 * except [onAddToPlaylist] and [onInfo], which open follow-up sheets.
 */
@Composable
fun SongActionsSheet(
    song: Song,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onInfo: () -> Unit,
    onShare: () -> Unit,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    extraActions: List<SongSheetAction> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AlbumArt(song.artworkPath, song.album, Modifier.size(56.dp), shape = SleeveShape, decorative = true)
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        song.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                QuickAction(Icons.Rounded.PlaylistPlay, R.string.sheets_actions_play_next, Modifier.weight(1f)) {
                    onPlayNext(); onDismiss()
                }
                QuickAction(Icons.AutoMirrored.Rounded.QueueMusic, R.string.sheets_actions_add_to_queue, Modifier.weight(1f)) {
                    onAddToQueue(); onDismiss()
                }
                ToggleButton(
                    checked = isFavorite,
                    onCheckedChange = { onToggleFavorite() },
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null)
                        Text(
                            stringResource(if (isFavorite) R.string.sheets_actions_unfavorite else R.string.sheets_actions_favorite),
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            ActionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.sheets_actions_add_to_playlist), onClick = onAddToPlaylist)
            if (onGoToAlbum != null) ActionRow(Icons.Rounded.Album, stringResource(R.string.sheets_actions_go_to_album)) { onDismiss(); onGoToAlbum() }
            if (onGoToArtist != null) ActionRow(Icons.Rounded.Person, stringResource(R.string.sheets_actions_go_to_artist)) { onDismiss(); onGoToArtist() }
            ActionRow(Icons.Rounded.Info, stringResource(R.string.sheets_actions_info), onClick = onInfo)
            ActionRow(Icons.Rounded.Share, stringResource(R.string.sheets_actions_share)) { onShare(); onDismiss() }
            extraActions.forEach { a ->
                ActionRow(a.icon, a.label, destructive = a.destructive) { onDismiss(); a.onClick() }
            }
            Spacer(Modifier.navigationBarsPadding().padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: Int, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.SmallContentPadding,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, null, tint = if (destructive) color else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
