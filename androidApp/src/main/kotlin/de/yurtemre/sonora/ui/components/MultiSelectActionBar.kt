package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.platform.ShareHelper
import de.yurtemre.sonora.ui.theme.SleeveColors
import kotlinx.coroutines.launch

/**
 * Multi-select action bar (UI-13). Action callbacks receive the selected ids; snackbars are shown here.
 * [allIds] feeds "select all"; [shareItems] maps selected ids to files for [ShareHelper].
 */
@Composable
fun MultiSelectActionBar(
    selection: SelectionState,
    allIds: () -> Collection<Long>,
    snackbarHostState: SnackbarHostState,
    onPlayNext: (Set<Long>) -> Unit,
    onAddToQueue: (Set<Long>) -> Unit,
    onAddToPlaylist: (Set<Long>) -> Unit,
    allFavorite: Boolean,
    onFavoriteToggle: (ids: Set<Long>, favorite: Boolean) -> Unit,
    shareItems: (Set<Long>) -> List<ShareHelper.Item>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val res = context.resources
    fun snack(text: String) = scope.launch { snackbarHostState.showSnackbar(text) }
    val ids = selection.selected
    val n = ids.size

    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(Icons.Rounded.Close, R.string.selection_close) { selection.clear() }
            Text(
                res.getQuantityString(R.plurals.selection_count, n, n),
                color = SleeveColors.Ink,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .background(SleeveColors.Label, MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            BarAction(Icons.Rounded.SelectAll, R.string.selection_select_all) { selection.selectAll(allIds()) }
            BarAction(Icons.Rounded.PlaylistPlay, R.string.selection_play_next) {
                onPlayNext(ids); snack(res.getQuantityString(R.plurals.selection_snack_play_next, n, n)); selection.clear()
            }
            BarAction(Icons.AutoMirrored.Rounded.QueueMusic, R.string.selection_add_to_queue) {
                onAddToQueue(ids); snack(res.getQuantityString(R.plurals.selection_snack_queued, n, n)); selection.clear()
            }
            BarAction(Icons.AutoMirrored.Rounded.PlaylistAdd, R.string.selection_add_to_playlist) { onAddToPlaylist(ids) }
            BarAction(
                if (allFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (allFavorite) R.string.selection_unfavorite else R.string.selection_favorite,
            ) {
                val fav = !allFavorite
                onFavoriteToggle(ids, fav)
                snack(
                    res.getQuantityString(
                        if (fav) R.plurals.selection_snack_favorited else R.plurals.selection_snack_unfavorited, n, n,
                    ),
                )
            }
            BarAction(Icons.Rounded.Share, R.string.selection_share) {
                if (!ShareHelper.share(context, shareItems(ids))) snack(res.getString(R.string.selection_snack_share_failed))
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = androidx.compose.ui.res.stringResource(label))
    }
}
