package de.yurtemre.sonora.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.library.ScrollSection
import de.yurtemre.sonora.ui.theme.SleeveColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Fast scroller (UI-07) overlay for a [LazyColumn]. Place in a Box over the list, aligned to end. */
@Composable
fun FastScroller(
    state: LazyListState,
    sectionAt: (index: Int) -> ScrollSection?,
    modifier: Modifier = Modifier,
) = FastScrollerImpl(
    total = { state.layoutInfo.totalItemsCount },
    first = { state.firstVisibleItemIndex },
    visible = { state.layoutInfo.visibleItemsInfo.size },
    scrolling = { state.isScrollInProgress },
    scrollTo = { state.scrollToItem(it) },
    sectionAt = sectionAt,
    modifier = modifier,
)

/** Fast scroller (UI-07) overlay for a [LazyVerticalGrid]. */
@Composable
fun FastScroller(
    state: LazyGridState,
    sectionAt: (index: Int) -> ScrollSection?,
    modifier: Modifier = Modifier,
) = FastScrollerImpl(
    total = { state.layoutInfo.totalItemsCount },
    first = { state.firstVisibleItemIndex },
    visible = { state.layoutInfo.visibleItemsInfo.size },
    scrolling = { state.isScrollInProgress },
    scrollTo = { state.scrollToItem(it) },
    sectionAt = sectionAt,
    modifier = modifier,
)

/** Localized bubble text for a [ScrollSection]. */
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

private val TouchWidth = 48.dp
private val ThumbHeight = 48.dp
private const val HIDE_DELAY_MS = 1500L

@Composable
private fun FastScrollerImpl(
    total: () -> Int,
    first: () -> Int,
    visible: () -> Int,
    scrolling: () -> Boolean,
    scrollTo: suspend (Int) -> Unit,
    sectionAt: (Int) -> ScrollSection?,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentSectionAt by rememberUpdatedState(sectionAt)
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }

    val count by remember { derivedStateOf(total) }
    val listFraction by remember {
        derivedStateOf {
            val scrollable = (total() - visible()).coerceAtLeast(1)
            (first().toFloat() / scrollable).coerceIn(0f, 1f)
        }
    }
    val isScrolling by remember { derivedStateOf(scrolling) }
    val fraction = if (dragging) dragFraction else listFraction
    val needed = count > visible() && count > 0

    LaunchedEffect(isScrolling, dragging) {
        if (isScrolling || dragging) shown = true else {
            delay(HIDE_DELAY_MS)
            shown = false
        }
    }

    fun indexFor(f: Float) = (f * (count - 1)).roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))
    var lastSection by remember { mutableStateOf<ScrollSection?>(null) }
    fun jump(f: Float) {
        dragFraction = f.coerceIn(0f, 1f)
        val idx = indexFor(dragFraction)
        val sec = currentSectionAt(idx)
        if (sec != null && sec != lastSection) {
            if (lastSection != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            lastSection = sec
        }
        scope.launch { scrollTo(idx) }
    }

    if (!needed) return
    val section = if (count > 0) sectionAt(indexFor(fraction)) else null
    val sectionText = section?.let { scrollSectionLabel(it) }
    val a11yLabel = stringResource(R.string.fast_scroller_label)
    val a11yState = sectionText ?: stringResource(R.string.fast_scroller_state, (fraction * 100).roundToInt())

    BoxWithConstraints(modifier.fillMaxHeight().width(TouchWidth + 96.dp)) {
        val trackPx = with(density) { (maxHeight - ThumbHeight).toPx() }.coerceAtLeast(1f)
        val thumbY = (fraction * trackPx).roundToInt()
        AnimatedVisibility(
            visible = shown || dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(TouchWidth)
                    .semantics {
                        contentDescription = a11yLabel
                        stateDescription = a11yState
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                        setProgress { jump(it); true }
                    }
                    .pointerInput(count) {
                        detectVerticalDragGestures(
                            onDragStart = { o ->
                                dragging = true
                                lastSection = null
                                jump((o.y - ThumbHeight.toPx() / 2) / trackPx)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, _ ->
                            change.consume()
                            jump((change.position.y - ThumbHeight.toPx() / 2) / trackPx)
                        }
                    },
            ) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, thumbY) }
                        .size(width = if (dragging) 8.dp else 4.dp, height = ThumbHeight)
                        .background(
                            if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(50),
                        ),
                )
            }
        }
        if (dragging && sectionText != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-TouchWidth.roundToPx(), thumbY) }
                    .widthIn(min = 56.dp)
                    .background(SleeveColors.Label, MaterialTheme.shapes.large)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(sectionText, color = SleeveColors.Ink, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
