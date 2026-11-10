package de.yurtemre.sonora.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.theme.LocalReduceMotion
import de.yurtemre.sonora.ui.theme.PillShape
import de.yurtemre.sonora.ui.theme.SleeveRowShape
import de.yurtemre.sonora.ui.theme.tabular
import de.yurtemre.sonora.ui.theme.SonoraTheme

/** Extra TalkBack action exposed on a [SongRow]. */
data class SongRowAction(val label: String, val onAction: () -> Unit)

/**
 * List row for a song (DESIGN §6.5): no card, no divider. Leading slot shows the track number in
 * tabular figures (or the sleeve when [showArtwork]); when [isPlaying] it swaps to Cobalt equalizer bars (static under [LocalReduceMotion]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    title: String,
    artist: String,
    durationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    artwork: Any? = null,
    showArtwork: Boolean = false,
    isPlaying: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    accessibilityActions: List<SongRowAction> = emptyList(),
) {
    val cs = MaterialTheme.colorScheme
    val duration = formatDuration(durationMs)
    val desc = stringResource(R.string.song_row_state_desc, title, artist, duration)
    val playingDesc = stringResource(R.string.cd_now_playing)
    val moreLabel = stringResource(R.string.cd_more_options)
    val bg = if (selected) cs.primaryContainer else Color.Transparent
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .clearAndSetSemantics {
                contentDescription = desc
                if (isPlaying) stateDescription = playingDesc
                this.selected = selected
                onClick { onClick(); true }
                if (onLongClick != null) onLongClick { onLongClick(); true }
                customActions = buildList {
                    if (onMoreClick != null) add(CustomAccessibilityAction(moreLabel) { onMoreClick(); true })
                    accessibilityActions.forEach { a -> add(CustomAccessibilityAction(a.label) { a.onAction(); true }) }
                }
            }
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                selected -> Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary)
                isPlaying -> EqualizerBars(cs.primary)
                showArtwork -> AlbumArt(artwork, title, Modifier.size(48.dp), shape = SleeveRowShape, decorative = true)
                else -> Text(
                    trackNumber?.toString() ?: "–",
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    color = cs.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = if (isPlaying) cs.primary else cs.onSurface,
            )
            Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        Text(duration, style = MaterialTheme.typography.labelMedium.tabular(), color = cs.onSurfaceVariant)
        if (onMoreClick != null) {
            IconButton(onClick = onMoreClick) { Icon(Icons.Rounded.MoreVert, moreLabel) }
        }
    }
}

@Composable
private fun EqualizerBars(color: Color) {
    val reduce = LocalReduceMotion.current
    val heights: List<State<Float>> = if (reduce) {
        listOf(0.5f, 0.85f, 0.65f).map { remember { mutableFloatStateOf(it) } }
    } else {
        val t = rememberInfiniteTransition(label = "eq")
        listOf(420, 300, 520).mapIndexed { i, ms ->
            t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(ms), RepeatMode.Reverse), label = "bar$i")
        }
    }
    Row(Modifier.size(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEach { h ->
            Box(Modifier.width(4.dp).fillMaxHeight(h.value).clip(PillShape).background(color))
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Preview(showBackground = true)
@Composable
private fun SongRowPreview() = SonoraTheme {
    Column {
        SongRow("Sleeve", "Aurora", 215_000, {}, trackNumber = 1, onMoreClick = {})
        SongRow("Night Drive", "Aurora", 3_725_000, {}, trackNumber = 2, isPlaying = true, onMoreClick = {})
        SongRow("Echoes", "Aurora", 185_000, {}, trackNumber = 3, selected = true, onMoreClick = {})
    }
}
