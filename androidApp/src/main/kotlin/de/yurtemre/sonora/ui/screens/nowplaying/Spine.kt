package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.yurtemre.sonora.shared.model.TitleStyle
import de.yurtemre.sonora.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

/** Font scale at which the Spine falls back to a horizontal title (DESIGN §8). */
const val SPINE_FALLBACK_FONT_SCALE = 1.5f

/** Above this font scale the marquee is off (as `a11yMarquee`): the title is clipped and the caption shows it. */
private const val MARQUEE_MAX_FONT_SCALE = 1.3f

fun spineIsHorizontal(fontScale: Float) = fontScale >= SPINE_FALLBACK_FONT_SCALE

/** The title style that is really drawn: the marquee needs motion and normal-sized text, else it behaves as Hybrid. */
@Composable
fun TitleStyle.resolved(): TitleStyle =
    if (this == TitleStyle.Marquee && (LocalReduceMotion.current || LocalDensity.current.fontScale >= MARQUEE_MAX_FONT_SCALE)) {
        TitleStyle.Hybrid
    } else this

/** Whether the title runs along the sleeve (all but [TitleStyle.Classic]). */
val TitleStyle.usesSpine: Boolean get() = this != TitleStyle.Classic

/** Widest the Spine gets next to the sleeve; two columns need more room. */
val TitleStyle.spineWidth: Dp get() = if (this == TitleStyle.TwoColumns) 96.dp else 72.dp

/**
 * The Spine (DESIGN §5): the song title bottom-to-top along the left edge in displayLarge condensed. What happens to
 * a title that is too long depends on [style]: it is clipped by the edge ([TitleStyle.Hybrid]; the caller shows the
 * full title under the cover, see [rememberSpineFits]), scrolls once ([TitleStyle.Marquee]), shrinks
 * ([TitleStyle.Fit]) or wraps into a second column ([TitleStyle.TwoColumns]). [TitleStyle.Classic] has no Spine:
 * a plain horizontal title. The rotated glyphs are decorative; the title is exposed once as a heading.
 */
@Composable
fun Spine(
    title: String,
    modifier: Modifier = Modifier,
    /** Lay the title out horizontally (large fonts, or panes too short for a vertical spine). */
    horizontal: Boolean = spineIsHorizontal(LocalDensity.current.fontScale),
    style: TitleStyle = TitleStyle.Hybrid,
    /** Marquee only: keep scrolling (settings preview) instead of once per track. */
    loopMarquee: Boolean = false,
    /** Horizontal title only: centre the text (the Classic title under a centred cover). */
    centered: Boolean = false,
) {
    val color = MaterialTheme.colorScheme.onSurface
    if (horizontal || style == TitleStyle.Classic) {
        Text(
            title,
            modifier = modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            color = color,
            textAlign = if (centered) TextAlign.Center else TextAlign.Unspecified,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val semantics = Modifier.clipToBounds().clearAndSetSemantics { heading(); contentDescription = title }
    when (style.resolved()) {
        TitleStyle.Marquee -> MarqueeSpine(title, modifier.then(semantics), loopMarquee)
        TitleStyle.Fit, TitleStyle.TwoColumns -> FittedSpine(title, style, modifier.then(semantics))
        else -> ClippedSpine(title, modifier.then(semantics))
    }
}

/**
 * Whether [title] fits on one line of the Spine at its full size along [along] (the sleeve height). Decided before
 * layout, so the Hybrid caption can claim its room up front instead of appearing after the first frame.
 */
@Composable
fun rememberSpineFits(title: String): (Dp) -> Boolean {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.displayLarge
    val density = LocalDensity.current
    return remember(title, measurer, style, density) {
        { along: Dp ->
            measurer.measure(AnnotatedString(title), style, maxLines = 1, softWrap = false).size.width <= with(density) { along.roundToPx() }
        }
    }
}

/** The title on one line, clipped by the edge. */
@Composable
private fun ClippedSpine(title: String, modifier: Modifier) {
    Box(modifier) {
        Text(
            title,
            modifier = Modifier.rotatedBottomToTop(unbounded = false),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * Scrolls a title that is too long once, after a short pause, to its end and back to its start, where it rests.
 * With [loop] it repeats (the settings preview). A title that fits does not move.
 */
@Composable
private fun MarqueeSpine(title: String, modifier: Modifier, loop: Boolean) {
    val scroll = remember(title) { Animatable(0f) }
    var textWidth by remember(title) { mutableIntStateOf(0) }
    var along by remember { mutableIntStateOf(0) }
    LaunchedEffect(title, textWidth, along, loop) {
        val overflow = textWidth - along
        if (overflow <= 0 || along == 0) { scroll.snapTo(0f); return@LaunchedEffect }
        do {
            delay(1_500)
            scroll.animateTo(overflow.toFloat(), tween((overflow * 6).coerceIn(1_500, 12_000), easing = LinearEasing))
            delay(1_200)
            scroll.animateTo(0f, tween(700))
        } while (loop)
    }
    Box(modifier.onSizeChanged { along = it.height }) {
        Text(
            title,
            modifier = Modifier.rotatedBottomToTop(unbounded = true) { scroll.value },
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            onTextLayout = { textWidth = it.size.width },
        )
    }
}

/**
 * [TitleStyle.Fit]: the largest type size (down to a floor) at which the title fits on one line.
 * [TitleStyle.TwoColumns]: displayLarge when it fits, else a smaller size wrapped into two columns.
 */
@Composable
private fun FittedSpine(title: String, style: TitleStyle, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val base = MaterialTheme.typography.displayLarge
    val small = MaterialTheme.typography.displaySmall
    BoxWithConstraints(modifier) {
        val alongPx = with(LocalDensity.current) { maxHeight.toPx() }.toInt()
        fun width(s: TextStyle) = measurer.measure(AnnotatedString(title), s, maxLines = 1, softWrap = false).size.width
        val (textStyle, lines) = remember(title, alongPx, base, small, style) {
            when {
                width(base) <= alongPx -> base to 1
                style == TitleStyle.TwoColumns -> small to 2
                else -> {
                    // Binary search the size in whole sp between the floor and the full size.
                    var lo = MIN_FIT_SP
                    var hi = base.fontSize.value.toInt()
                    while (lo < hi) {
                        val mid = (lo + hi + 1) / 2
                        if (width(base.copy(fontSize = mid.sp)) <= alongPx) lo = mid else hi = mid - 1
                    }
                    base.copy(fontSize = lo.sp) to 1
                }
            }
        }
        Text(
            title,
            modifier = Modifier.rotatedBottomToTop(unbounded = false),
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = lines,
            softWrap = lines > 1,
            overflow = if (lines > 1) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}

private const val MIN_FIT_SP = 28

/**
 * Measures the child along the parent's height and places it rotated −90° (reads bottom-to-top), starting at the
 * bottom edge. With [unbounded] the child may be longer than the parent (the excess is clipped) and [offset]
 * (pixels) slides it down along the spine to reveal the end.
 */
private fun Modifier.rotatedBottomToTop(unbounded: Boolean, offset: () -> Float = { 0f }): Modifier = layout { measurable, constraints ->
    val along = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
    val p = measurable.measure(Constraints(maxWidth = if (unbounded) Constraints.Infinity else along))
    val height = if (along == Constraints.Infinity) p.width else minOf(p.width, along)
    layout(p.height, height) {
        p.placeWithLayer((p.height - p.width) / 2, height - (p.width + p.height) / 2) {
            rotationZ = -90f
            translationY = offset()
        }
    }
}
