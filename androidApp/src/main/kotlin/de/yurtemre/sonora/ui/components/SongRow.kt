package de.yurtemre.sonora.ui.components

import de.yurtemre.sonora.ui.format.DurationFormat
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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.model.Song
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
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

/** One swipe direction of a [SongRow]: coloured reveal with [icon]; the row springs back after [onSwipe]. */
data class SongSwipe(val label: String, val icon: ImageVector, val container: Color, val content: Color, val onSwipe: () -> Unit)

/** Per-screen swipe actions (DESIGN §6b.2): [right] = swipe start→end, [left] = end→start. */
data class SongSwipeActions(val right: SongSwipe?, val left: SongSwipe?)

/**
 * Default row swipes: right = play next (Label / secondaryContainer), left = add to queue
 * (Cobalt / primaryContainer). Shows a snackbar with undo on [snackbar] when given.
 * Pass [left] to replace the add-to-queue action (e.g. remove from playlist).
 */
@Composable
fun rememberQueueSwipeActions(song: Song, snackbar: SnackbarHostState?, left: SongSwipe? = null): SongSwipeActions {
    val playback = koinInject<PlaybackConnection>()
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    fun withUndo(msg: String, act: () -> Unit) {
        val before = playback.queue.value.items.mapTo(HashSet()) { it.entryUid }
        act()
        if (snackbar == null) return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(msg, res.getString(R.string.home2_undo), duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) {
                playback.queue.value.items.lastOrNull { it.entryUid !in before && it.song.id == song.id }
                    ?.let { playback.removeFromQueue(it.entryUid) }
            }
        }
    }
    val playNext = SongSwipe(
        res.getString(R.string.songs_play_next), Icons.Rounded.PlaylistPlay, cs.secondaryContainer, cs.onSecondaryContainer,
    ) { withUndo(res.getString(R.string.sheets_actions_snack_play_next, song.title)) { playback.playNext(listOf(song)) } }
    val queue = left ?: SongSwipe(
        res.getString(R.string.songs_add_to_queue), Icons.AutoMirrored.Rounded.QueueMusic, cs.primaryContainer, cs.onPrimaryContainer,
    ) { withUndo(res.getString(R.string.sheets_actions_snack_queued, song.title)) { playback.addToQueue(listOf(song)) } }
    return SongSwipeActions(playNext, queue)
}

/**
 * List row for a song (DESIGN §6.5, §6b.2): no card, no divider, no ⋮ — long-press (or [onMoreClick] when
 * [onLongClick] is null) opens the action sheet, [swipeActions] add swipe right/left; both are TalkBack actions. Leading slot shows the track number in
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
    swipeActions: SongSwipeActions? = null,
    swipeEnabled: Boolean = true,
) {
    if (swipeActions == null || !swipeEnabled) {
        SongRowContent(title, artist, durationMs, onClick, modifier, trackNumber, artwork, showArtwork, isPlaying, selected,
            onLongClick, onMoreClick, accessibilityActions)
        return
    }
    val state = rememberSwipeToDismissBoxState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) {
        snapshotFlow { state.targetValue }.collect {
            if (it != SwipeToDismissBoxValue.Settled) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }
    }
    val swipeA11y = listOfNotNull(swipeActions.right, swipeActions.left).map { SongRowAction(it.label, it.onSwipe) }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = swipeActions.right != null,
        enableDismissFromEndToStart = swipeActions.left != null,
        onDismiss = { v ->
            // Act, then spring back: rows are never removed by these swipes.
            (if (v == SwipeToDismissBoxValue.StartToEnd) swipeActions.right else swipeActions.left)?.onSwipe?.invoke()
            scope.launch { state.reset() }
        },
        backgroundContent = {
            val right = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val a = if (right) swipeActions.right else swipeActions.left
            if (a != null && state.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Box(
                    Modifier.fillMaxSize().background(a.container).padding(horizontal = 24.dp),
                    contentAlignment = if (right) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!right) Text(a.label, style = MaterialTheme.typography.labelLarge, color = a.content)
                        Icon(a.icon, null, tint = a.content)
                        if (right) Text(a.label, style = MaterialTheme.typography.labelLarge, color = a.content)
                    }
                }
            }
        },
    ) {
        SongRowContent(title, artist, durationMs, onClick, Modifier.background(MaterialTheme.colorScheme.surface), trackNumber, artwork,
            showArtwork, isPlaying, selected, onLongClick, onMoreClick, swipeA11y + accessibilityActions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRowContent(
    title: String,
    artist: String,
    durationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier,
    trackNumber: Int?,
    artwork: Any?,
    showArtwork: Boolean,
    isPlaying: Boolean,
    selected: Boolean,
    onLongClick: (() -> Unit)?,
    onMoreClick: (() -> Unit)?,
    accessibilityActions: List<SongRowAction>,
) {
    val longPress = onLongClick ?: onMoreClick
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
            .combinedClickable(onClick = onClick, onLongClick = longPress)
            .clearAndSetSemantics {
                contentDescription = desc
                if (isPlaying) stateDescription = playingDesc
                this.selected = selected
                onClick { onClick(); true }
                if (longPress != null) onLongClick { longPress(); true }
                customActions = buildList {
                    if (onMoreClick != null) add(CustomAccessibilityAction(moreLabel) { onMoreClick(); true })
                    accessibilityActions.forEach { a -> add(CustomAccessibilityAction(a.label) { a.onAction(); true }) }
                }
            }
            .padding(start = 16.dp, end = 16.dp),
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

private fun formatDuration(ms: Long): String = DurationFormat.clockMs(ms)

@Preview(showBackground = true)
@Composable
private fun SongRowPreview() = SonoraTheme {
    Column {
        SongRow("Sleeve", "Aurora", 215_000, {}, trackNumber = 1, onMoreClick = {})
        SongRow("Night Drive", "Aurora", 3_725_000, {}, trackNumber = 2, isPlaying = true, onMoreClick = {})
        SongRow("Echoes", "Aurora", 185_000, {}, trackNumber = 3, selected = true, onMoreClick = {})
    }
}
