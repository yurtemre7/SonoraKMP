package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.theme.LocalReduceMotion
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.navigation.icon
import de.yurtemre.sonora.ui.navigation.labelRes
import de.yurtemre.sonora.ui.theme.SleeveShape

/**
 * Songs / Albums / Artists / Playlists as a 2x2 grid of tiles; a tap opens the Library on that tab, a long press opens
 * a one-item menu with a shuffle quick action ([onQuickShuffle], disabled when [quickEnabled] says there is nothing to play).
 */
@Composable
fun HomeTiles(
    onOpenTab: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
    quickEnabled: (LibraryTab) -> Boolean = { true },
    onQuickShuffle: (LibraryTab) -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LibraryTab.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tab ->
                    Tile(tab, { onOpenTab(tab) }, quickEnabled(tab), { onQuickShuffle(tab) }, Modifier.weight(1f))
                }
            }
        }
    }
}

private fun quickLabelRes(tab: LibraryTab): Int = when (tab) {
    LibraryTab.Songs -> R.string.home_tile_shuffle_songs
    LibraryTab.Albums -> R.string.home_tile_shuffle_album
    LibraryTab.Artists -> R.string.home_tile_shuffle_artist
    LibraryTab.Playlists -> R.string.home_tile_shuffle_playlist
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(tab: LibraryTab, onClick: () -> Unit, quickEnabled: Boolean, onQuick: () -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tileScale",
    )
    var menu by remember { mutableStateOf(false) }
    Box(modifier.scale(scale)) {
        Surface(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                },
            ),
            shape = SleeveShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(tab.icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(tab.labelRes),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(quickLabelRes(tab))) },
                leadingIcon = { Icon(Icons.Rounded.Shuffle, null) },
                enabled = quickEnabled,
                onClick = {
                    menu = false
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onQuick()
                },
            )
        }
    }
}
