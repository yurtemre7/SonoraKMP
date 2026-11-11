package de.yurtemre.sonora.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.library.ScrollSection
import de.yurtemre.sonora.ui.theme.SleeveColors
import de.yurtemre.sonora.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Localized text for a shared [ScrollSection]. */
@Composable
fun scrollSectionLabel(section: ScrollSection): String = when (section) {
    is ScrollSection.Letter ->
        if (section.letter == '#') stringResource(R.string.scroll_section_letter_other) else section.letter.toString()
    is ScrollSection.Minutes -> {
        val m = section.minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(R.plurals.scroll_section_minutes, m, m)
    }
    is ScrollSection.PlayCount -> pluralStringResource(R.plurals.scroll_section_plays, section.count, section.count)
}

/**
 * Tab-strip index (DESIGN §6b.1) for a list split by [BinDivider]s. [starts] are the lazy indices of
 * each divider, [labels] their texts. Dragging along the right edge jumps between dividers and
 * highlights the current tab, with a haptic tick per change. Place in a Box over the list, aligned to end.
 */
@Composable
fun BinIndexStrip(state: LazyListState, labels: List<String>, starts: List<Int>, modifier: Modifier = Modifier) =
    BinIndexStripImpl(labels, starts, { state.firstVisibleItemIndex }, { state.isScrollInProgress }, { state.scrollToItem(it) }, modifier)

/** [BinIndexStrip] for a lazy grid. */
@Composable
fun BinIndexStrip(state: LazyGridState, labels: List<String>, starts: List<Int>, modifier: Modifier = Modifier) =
    BinIndexStripImpl(labels, starts, { state.firstVisibleItemIndex }, { state.isScrollInProgress }, { state.scrollToItem(it) }, modifier)

private const val HIDE_DELAY_MS = 1500L
private val RowHeight = 18.dp

@Composable
private fun BinIndexStripImpl(
    labels: List<String>,
    starts: List<Int>,
    first: () -> Int,
    scrolling: () -> Boolean,
    scrollTo: suspend (Int) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentStarts by rememberUpdatedState(starts)
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    val listIndex by remember { derivedStateOf { currentStarts.indexOfLast { it <= first() }.coerceAtLeast(0) } }
    val isScrolling by remember { derivedStateOf(scrolling) }
    LaunchedEffect(isScrolling, dragging) {
        if (isScrolling || dragging) shown = true else { delay(HIDE_DELAY_MS); shown = false }
    }
    val n = labels.size
    if (n < 2 || n != starts.size) return
    val current = (if (dragging) dragIndex else listIndex).coerceIn(0, n - 1)

    fun jump(i: Int) {
        val idx = i.coerceIn(0, n - 1)
        if (idx != dragIndex) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        dragIndex = idx
        scope.launch { scrollTo(currentStarts[idx]) }
    }

    val a11yLabel = stringResource(R.string.home2_index_label)
    AnimatedVisibility(shown || dragging, modifier.fillMaxHeight(), enter = fadeIn(), exit = fadeOut()) {
        BoxWithConstraints(Modifier.fillMaxHeight().width(48.dp).padding(vertical = 8.dp)) {
            val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            // More tabs than fit → keep every step-th label (plus the current one).
            val fit = (maxHeight / RowHeight).toInt().coerceAtLeast(2)
            val step = ((n + fit - 1) / fit).coerceAtLeast(1)
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = a11yLabel
                        stateDescription = labels[current]
                        progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), 0f..(n - 1).toFloat(), steps = (n - 2).coerceAtLeast(0))
                        setProgress { jump(it.roundToInt()); true }
                    }
                    .pointerInput(n) {
                        fun at(y: Float) = (y / heightPx * n).toInt()
                        detectVerticalDragGestures(
                            onDragStart = { o -> dragIndex = listIndex; dragging = true; jump(at(o.y)) },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, _ -> change.consume(); jump(at(change.position.y)) }
                    },
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.End,
            ) {
                labels.forEachIndexed { i, label ->
                    if (i % step == 0 || i == current) IndexTab(label, active = i == current, expanded = dragging && i == current)
                }
            }
        }
    }
}

@Composable
private fun IndexTab(label: String, active: Boolean, expanded: Boolean) {
    val short = label.length <= 2
    Box(Modifier.padding(end = 6.dp).wrapContentWidth(Alignment.End, unbounded = true), contentAlignment = Alignment.CenterEnd) {
        when {
            active -> Text(
                if (short || expanded) label else label.take(1),
                color = SleeveColors.Ink,
                style = (if (expanded) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium).tabular(),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .background(SleeveColors.Label, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 2.dp, bottomEnd = 2.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            short -> Text(label, style = MaterialTheme.typography.labelSmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
        }
    }
}
