package de.yurtemre.sonora.ui.screens.changelog

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
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
 */
@Composable
fun SleeveMarkdown(content: String, modifier: Modifier = Modifier) {
    val parsed by produceState<State?>(null, content) {
        value = withContext(Dispatchers.Default) { parseMarkdown(content) }
    }
    val state = parsed ?: return
    val t = MaterialTheme.typography
    Markdown(
        state = state,
        modifier = modifier,
        typography = markdownTypography(
            h1 = t.headlineMediumEmphasized,
            h2 = t.titleLarge,
            h3 = LocalSleeveExtras.current.sectionHeader,
            h4 = t.titleSmall,
            h5 = t.titleSmall,
            h6 = t.titleSmall,
            text = t.bodyMedium,
            paragraph = t.bodyMedium,
        ),
        animations = markdownAnimations(animateTextSize = { this }),
    )
}
