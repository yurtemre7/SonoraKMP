package de.yurtemre.sonora.ui.screens.home.tabs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.library.SmartListKind

fun SmartListKind.titleRes(): Int = when (this) {
    SmartListKind.RecentlyAdded -> R.string.smart_list_recently_added
    SmartListKind.NeverPlayed -> R.string.smart_list_never_played
    SmartListKind.MostPlayed -> R.string.smart_list_most_played
}

fun SmartListKind.emptyRes(): Int = when (this) {
    SmartListKind.RecentlyAdded -> R.string.smart_list_empty_recently_added
    SmartListKind.NeverPlayed -> R.string.smart_list_empty_never_played
    SmartListKind.MostPlayed -> R.string.smart_list_empty_most_played
}

fun SmartListKind.icon(): ImageVector = when (this) {
    SmartListKind.RecentlyAdded -> Icons.Rounded.NewReleases
    SmartListKind.NeverPlayed -> Icons.Rounded.MusicOff
    SmartListKind.MostPlayed -> Icons.Rounded.TrendingUp
}

/** Chips that open the built-in smart lists (recently added, never played, most played). */
@Composable
fun SmartListsRow(onOpen: (SmartListKind) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmartListKind.entries.forEach { kind ->
            AssistChip(
                onClick = { onOpen(kind) },
                label = { Text(stringResource(kind.titleRes())) },
                leadingIcon = { Icon(kind.icon(), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }
}
