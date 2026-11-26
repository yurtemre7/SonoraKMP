package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.stats.HomeGlance
import de.yurtemre.sonora.ui.format.currentLocale
import de.yurtemre.sonora.ui.theme.SleeveShape
import java.text.NumberFormat

/** Songs, plays, approximate listening time and top artist; tapping opens the statistics screen. */
@Composable
fun StatsGlanceCard(glance: HomeGlance, onOpenStats: () -> Unit, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val desc = stringResource(R.string.home_card_stats_open)
    Surface(
        onClick = onOpenStats,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = desc },
        shape = SleeveShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Insights, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.home_card_stats_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(numbers.format(glance.songCount), stringResource(R.string.home_card_stats_songs), Modifier.weight(1f))
                Stat(numbers.format(glance.plays), stringResource(R.string.home_card_stats_plays), Modifier.weight(1f))
                Stat(listenedText(glance.listenedMs), stringResource(R.string.home_card_stats_listened), Modifier.weight(1.4f))
            }
            Column {
                Text(
                    stringResource(R.string.home_card_stats_top_artist),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    glance.topArtist ?: stringResource(R.string.home_card_stats_none),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "12h 30m" / "45m" from the plays x duration estimate (no seconds: it is approximate). */
@Composable
private fun listenedText(ms: Long): String {
    val totalMin = (ms.coerceAtLeast(0) / 60_000).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    val minutes = stringResource(R.string.duration_part_minutes, m)
    if (h == 0) return minutes
    val hours = stringResource(R.string.duration_part_hours, h)
    return hours + stringResource(R.string.duration_part_separator) + minutes
}
