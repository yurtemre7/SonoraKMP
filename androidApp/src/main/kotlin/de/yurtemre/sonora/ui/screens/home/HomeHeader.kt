package de.yurtemre.sonora.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.components.DevBadge
import java.time.LocalTime

/** Greeting resource for the hour (UI-04): <12 morning, <17 afternoon, else evening. */
internal fun greetingRes(hour: Int = LocalTime.now().hour): Int = when {
    hour < 12 -> R.string.good_morning
    hour < 17 -> R.string.good_afternoon
    else -> R.string.good_evening
}

/** Collapsing large top bar of Home (UI-04). [userName] non-null → greeting title. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeHeader(
    userName: String?,
    isSyncing: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    onRecentlyPlayed: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit,
) {
    val title = if (userName != null) stringResource(greetingRes(), userName) else stringResource(R.string.app_name)
    Column {
        LargeFlexibleTopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).semantics { heading() })
                    Spacer(Modifier.width(8.dp))
                    DevBadge()
                }
            },
            actions = {
                HeaderAction(Icons.Rounded.History, stringResource(R.string.home_action_recently_played), onRecentlyPlayed)
                HeaderAction(Icons.Rounded.FavoriteBorder, stringResource(R.string.home_action_favorites), onFavorites)
                HeaderAction(Icons.Rounded.Settings, stringResource(R.string.home_action_settings), onSettings)
            },
            colors = TopAppBarDefaults.topAppBarColors(),
            scrollBehavior = scrollBehavior,
        )
        if (isSyncing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        else Spacer(Modifier.height(2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeaderAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}
