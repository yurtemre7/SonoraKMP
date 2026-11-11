package de.yurtemre.sonora.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.model.SongSort
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

/** Fast-scroll section key (DESIGN §6b.1). Letters / minutes / plays come from the shared [ScrollSection]. */
@Immutable
sealed interface BinKey {
    data class Section(val section: ScrollSection) : BinKey
    /** 0 = this week, 1 = this month, 2 = earlier. */
    data class Added(val bucket: Int) : BinKey
    /** Count band [from]..[to] (`to == null` → open-ended). [plays] picks the "plays" wording. */
    data class Band(val from: Int, val to: Int?, val plays: Boolean = false) : BinKey
}

/** A run of consecutive items sharing one [key]. */
@Immutable
data class Bin<T>(val key: BinKey, val items: List<T>)

/** Groups consecutive items with the same key (works for either sort direction). */
fun <T> binsOf(items: List<T>, keyOf: (T) -> BinKey): List<Bin<T>> {
    val out = ArrayList<Bin<T>>()
    var key: BinKey? = null
    var run = ArrayList<T>()
    for (item in items) {
        val k = keyOf(item)
        if (k != key && run.isNotEmpty()) { out += Bin(key!!, run); run = ArrayList() }
        key = k
        run += item
    }
    if (run.isNotEmpty()) out += Bin(key!!, run)
    return out
}

private val CountBands = intArrayOf(1, 5, 10, 25, 50, 100)

/** Band for a count: 0, 1–4, 5–9, 10–24, 25–49, 50–99, 100+. */
fun countBand(n: Int, plays: Boolean = false): BinKey.Band {
    if (n <= 0) return BinKey.Band(0, 0, plays)
    val i = CountBands.indexOfLast { n >= it }
    val to = CountBands.getOrNull(i + 1)?.minus(1)
    return BinKey.Band(CountBands[i], to, plays)
}

private const val DAY_MS = 86_400_000L

/** "This week / This month / Earlier" bucket for a date-added timestamp. */
fun addedBin(dateAddedMs: Long?, now: Long = System.currentTimeMillis()): BinKey.Added {
    val age = now - (dateAddedMs ?: 0L)
    return BinKey.Added(if (age < 7 * DAY_MS) 0 else if (age < 30 * DAY_MS) 1 else 2)
}

/** Divider key for a song row under [sort]. Duration uses two-minute ranges. */
fun songBin(song: Song, sort: SongSort, plays: (Song) -> Int): BinKey = when (sort) {
    SongSort.Recent -> addedBin(song.dateAddedMs ?: song.lastModifiedMs)
    SongSort.Plays -> countBand(plays(song), plays = true)
    SongSort.Duration -> {
        val m = (song.durationMs / 60_000).toInt()
        if (m >= 10) BinKey.Band(10, null) else (m / 2 * 2).let { BinKey.Band(it, it + 2) }
    }
    else -> BinKey.Section(ScrollSection.forSong(song, sort))
}

fun letterBin(text: String): BinKey = BinKey.Section(ScrollSection.letterOf(text))

/** Localized divider text. */
@Composable
fun binLabel(key: BinKey): String = when (key) {
    is BinKey.Section -> scrollSectionLabel(key.section)
    is BinKey.Added -> stringResource(
        when (key.bucket) { 0 -> R.string.home2_bin_this_week; 1 -> R.string.home2_bin_this_month; else -> R.string.home2_bin_earlier },
    )
    is BinKey.Band -> when {
        key.plays && key.to == 0 -> stringResource(R.string.home2_bin_not_played)
        key.plays && key.to == null -> stringResource(R.string.home2_bin_plays_open, key.from)
        key.plays -> stringResource(R.string.home2_bin_plays, key.from, key.to!!)
        key.from == 10 && key.to == null -> stringResource(R.string.home2_bin_minutes_open, key.from)
        key.to == key.from + 2 -> stringResource(R.string.home2_bin_minutes, key.from, key.to!!)
        key.to == null -> stringResource(R.string.home2_bin_range_open, key.from)
        else -> stringResource(R.string.home2_bin_range, key.from, key.to)
    }
}

/** Lazy index of each bin's first item, given [leading] items before the first bin. */
fun binStarts(bins: List<Bin<*>>, leading: Int = 0): List<Int> {
    var i = leading
    return bins.map { b -> i.also { i += b.items.size } }
}

/**
 * Quiet fast scroller (DESIGN §6b.1): a thin thumb on the right edge that appears while scrolling,
 * with a small neutral bubble showing the current section while dragging. [starts] are the lazy
 * indices where each section begins, [labels] their texts. Place in a Box over the list, aligned to end.
 */
@Composable
fun FastScroller(state: LazyListState, labels: List<String>, starts: List<Int>, modifier: Modifier = Modifier) =
    FastScrollerImpl(labels, starts, { state.firstVisibleItemIndex }, { state.layoutInfo.totalItemsCount }, { state.isScrollInProgress }, { state.scrollToItem(it) }, modifier)

/** [FastScroller] for a lazy grid. */
@Composable
fun FastScroller(state: LazyGridState, labels: List<String>, starts: List<Int>, modifier: Modifier = Modifier) =
    FastScrollerImpl(labels, starts, { state.firstVisibleItemIndex }, { state.layoutInfo.totalItemsCount }, { state.isScrollInProgress }, { state.scrollToItem(it) }, modifier)

private const val HIDE_DELAY_MS = 1500L
private val ThumbHeight = 48.dp

@Composable
private fun FastScrollerImpl(
    labels: List<String>,
    starts: List<Int>,
    first: () -> Int,
    total: () -> Int,
    scrolling: () -> Boolean,
    scrollTo: suspend (Int) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentStarts by rememberUpdatedState(starts)
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    val listFraction by remember {
        derivedStateOf { val t = total(); if (t <= 1) 0f else first().toFloat() / (t - 1) }
    }
    val isScrolling by remember { derivedStateOf(scrolling) }
    LaunchedEffect(isScrolling, dragging) {
        if (isScrolling || dragging) shown = true else { delay(HIDE_DELAY_MS); shown = false }
    }
    val n = labels.size
    if (n == 0 || n != starts.size) return
    val fraction = (if (dragging) dragFraction else listFraction).coerceIn(0f, 1f)
    fun sectionAt(index: Int) = currentStarts.indexOfLast { it <= index }.coerceAtLeast(0)
    val section = sectionAt((fraction * (total() - 1).coerceAtLeast(0)).roundToInt())

    fun dragTo(f: Float) {
        val t = total()
        if (t <= 0) return
        val idx = (f.coerceIn(0f, 1f) * (t - 1)).roundToInt()
        if (sectionAt(idx) != section) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        dragFraction = f.coerceIn(0f, 1f)
        scope.launch { scrollTo(idx) }
    }

    val a11yLabel = stringResource(R.string.home2_index_label)
    AnimatedVisibility(shown || dragging, modifier.fillMaxHeight(), enter = fadeIn(), exit = fadeOut()) {
        BoxWithConstraints(Modifier.fillMaxHeight().width(48.dp).padding(vertical = 8.dp)) {
            val trackPx = (constraints.maxHeight - with(LocalDensity.current) { ThumbHeight.toPx() }).coerceAtLeast(1f)
            val thumbY = with(LocalDensity.current) { (fraction * trackPx).toDp() }
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = a11yLabel
                        stateDescription = labels[section]
                        progressBarRangeInfo = ProgressBarRangeInfo(section.toFloat(), 0f..(n - 1).coerceAtLeast(1).toFloat(), steps = (n - 2).coerceAtLeast(0))
                        setProgress { v ->
                            val s = v.roundToInt().coerceIn(0, n - 1)
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            scope.launch { scrollTo(currentStarts[s]) }
                            true
                        }
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { o -> dragging = true; dragTo((o.y - ThumbHeight.toPx() / 2) / trackPx) },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, _ -> change.consume(); dragTo((change.position.y - ThumbHeight.toPx() / 2) / trackPx) }
                    },
            ) {
                Row(
                    Modifier.align(Alignment.TopEnd).offset(y = thumbY).height(ThumbHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dragging) {
                        Text(
                            labels[section],
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium.tabular(),
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .wrapContentWidth(Alignment.End, unbounded = true)
                                .padding(end = 8.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    Box(
                        Modifier
                            .padding(end = 4.dp)
                            .width(if (dragging) 6.dp else 4.dp)
                            .fillMaxHeight()
                            .background(
                                if (dragging) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}
