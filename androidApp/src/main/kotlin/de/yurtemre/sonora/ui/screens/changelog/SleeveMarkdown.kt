package de.yurtemre.sonora.ui.screens.changelog

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnimations
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownExtendedSpans
import com.mikepenz.markdown.model.MarkdownInlineContent
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.parseMarkdown
import de.yurtemre.sonora.ui.theme.LocalSleeveExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Markdown in Sleeve type: h1 headline, h2 title, h3 section header; body text bodyMedium.
 *
 * The text is parsed once per [content] off the main thread and the parsed state is handed to the renderer.
 * Letting the renderer parse by itself made it drop back to its empty loading state on recompositions (app bar
 * collapsing, update download progress), which flashed the whole text. Size animations are off for the same reason.
 *
 * Every renderer setting (colors, typography, padding, components, ...) is also created once per theme and reused.
 * The library's factories return a new object on each call, and it hands them to every element through composition
 * locals, so any recomposition of the caller (e.g. the update sheet on progress or a once-a-second tick) re-built
 * and re-laid out the whole text.
 */
@Composable
fun SleeveMarkdown(content: String, modifier: Modifier = Modifier) {
    val parsed by produceState<State?>(null, content) {
        value = withContext(Dispatchers.Default) { parseMarkdown(content) }
    }
    val state = parsed ?: return
    MarkdownBody(state, rememberSleeveMarkdownConfig(), modifier)
}

/** Renderer settings held for as long as the theme doesn't change. */
private class SleeveMarkdownConfig(
    val colors: MarkdownColors,
    val typography: MarkdownTypography,
    val padding: MarkdownPadding,
    val dimens: MarkdownDimens,
    val annotator: MarkdownAnnotator,
    val extendedSpans: MarkdownExtendedSpans,
    val inlineContent: MarkdownInlineContent,
    val components: MarkdownComponents,
    val animations: MarkdownAnimations,
)

@Composable
private fun rememberSleeveMarkdownConfig(): SleeveMarkdownConfig {
    val scheme = MaterialTheme.colorScheme
    val t = MaterialTheme.typography
    val sectionHeader = LocalSleeveExtras.current.sectionHeader
    // The factories are composable, so they run on every call; only the instance from the first call per theme is kept.
    val colors = markdownColor()
    val typography = markdownTypography(
        h1 = t.headlineMediumEmphasized,
        h2 = t.titleLarge,
        h3 = sectionHeader,
        h4 = t.titleSmall,
        h5 = t.titleSmall,
        h6 = t.titleSmall,
        text = t.bodyMedium,
        paragraph = t.bodyMedium,
    )
    val padding = markdownPadding()
    val dimens = markdownDimens()
    val extendedSpans = markdownExtendedSpans()
    val inlineContent = markdownInlineContent()
    val animations = markdownAnimations(animateTextSize = { this })
    return remember(scheme, t, sectionHeader) {
        SleeveMarkdownConfig(
            colors = colors,
            typography = typography,
            padding = padding,
            dimens = dimens,
            annotator = markdownAnnotator(),
            extendedSpans = extendedSpans,
            inlineContent = inlineContent,
            components = markdownComponents(checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) }),
            animations = animations,
        )
    }
}

/** Skips while [state] and [config] are the same instances, so caller recompositions never reach the text. */
@Composable
private fun MarkdownBody(state: State, config: SleeveMarkdownConfig, modifier: Modifier) {
    Markdown(
        state = state,
        colors = config.colors,
        typography = config.typography,
        modifier = modifier,
        padding = config.padding,
        dimens = config.dimens,
        annotator = config.annotator,
        extendedSpans = config.extendedSpans,
        inlineContent = config.inlineContent,
        components = config.components,
        animations = config.animations,
    )
}
