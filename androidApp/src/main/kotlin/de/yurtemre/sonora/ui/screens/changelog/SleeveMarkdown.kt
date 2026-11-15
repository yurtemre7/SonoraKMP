package de.yurtemre.sonora.ui.screens.changelog

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.ui.theme.LocalSleeveExtras

/**
 * Markdown in Sleeve type: h1 headline, h2 title, h3 section header; body text bodyMedium.
 *
 * Plain Compose text, no markdown library: the source is parsed once per [content] into immutable blocks
 * ([MarkdownParser]) and each block is one skippable composable, so recomposing the caller (download progress in the
 * update dialog, a scrolling changelog) never rebuilds or re-lays out the text. The old library renderer did, which
 * flashed the changelog and the update notes.
 */
@Composable
fun SleeveMarkdown(content: String, modifier: Modifier = Modifier) {
    val document = remember(content) { MarkdownParser.parse(content) }
    SleeveMarkdown(document, modifier)
}

/** Renders an already parsed [document]. */
@Composable
fun SleeveMarkdown(document: MdDocument, modifier: Modifier = Modifier) {
    val style = rememberMarkdownStyle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        document.blocks.forEach { block -> MarkdownBlock(block, style) }
    }
}

/** Theme-derived text styles and colours, held until the theme changes. */
@Immutable
private class MarkdownStyle(
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val body: TextStyle,
    val code: TextStyle,
    val color: Color,
    val link: Color,
    val codeBackground: Color,
)

@Composable
private fun rememberMarkdownStyle(): MarkdownStyle {
    val scheme = MaterialTheme.colorScheme
    val t = MaterialTheme.typography
    val sectionHeader = LocalSleeveExtras.current.sectionHeader
    return remember(scheme, t, sectionHeader) {
        MarkdownStyle(
            h1 = t.headlineMediumEmphasized, h2 = t.titleLarge, h3 = sectionHeader, h4 = t.titleSmall,
            body = t.bodyMedium, code = t.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = scheme.onSurface, link = scheme.primary, codeBackground = scheme.surfaceContainerHighest,
        )
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, style: MarkdownStyle) {
    when (block) {
        is MdBlock.Heading -> {
            val text = rememberAnnotated(block.spans, style)
            val ts = when (block.level) { 1 -> style.h1; 2 -> style.h2; 3 -> style.h3; else -> style.h4 }
            Text(text, style = ts, color = style.color, modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp))
        }
        is MdBlock.Paragraph -> Text(rememberAnnotated(block.spans, style), style = style.body, color = style.color)
        is MdBlock.Item -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
            Text(block.marker, style = style.body, color = style.color, modifier = Modifier.width(24.dp))
            Text(rememberAnnotated(block.spans, style), style = style.body, color = style.color, modifier = Modifier.weight(1f))
        }
        is MdBlock.Code -> Text(
            block.text,
            style = style.code,
            color = style.color,
            softWrap = false,
            modifier = Modifier.clip(MaterialTheme.shapes.small).background(style.codeBackground)
                .horizontalScroll(rememberScrollState()).padding(12.dp),
        )
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun rememberAnnotated(spans: List<MdSpan>, style: MarkdownStyle): AnnotatedString =
    remember(spans, style) {
        buildAnnotatedString {
            for (s in spans) {
                val look = SpanStyle(
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                    textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (s.code) FontFamily.Monospace else null,
                    background = if (s.code) style.codeBackground else Color.Unspecified,
                )
                val url = s.url
                if (url == null) {
                    withStyle(look) { append(s.text) }
                } else {
                    val link = LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = style.link, textDecoration = TextDecoration.Underline)))
                    withLink(link) { withStyle(look) { append(s.text) } }
                }
            }
        }
    }
