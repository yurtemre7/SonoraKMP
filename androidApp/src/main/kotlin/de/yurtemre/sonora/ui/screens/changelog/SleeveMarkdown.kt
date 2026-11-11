package de.yurtemre.sonora.ui.screens.changelog

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import de.yurtemre.sonora.ui.theme.LocalSleeveExtras

/** Markdown in Sleeve type: h1 headline, h2 title, h3 section header; body text bodyMedium. */
@Composable
fun SleeveMarkdown(content: String, modifier: Modifier = Modifier) {
    val t = MaterialTheme.typography
    Markdown(
        content = content,
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
    )
}
