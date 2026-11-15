package de.yurtemre.sonora.ui.screens.changelog

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownParserTest {
    private fun blocks(md: String) = MarkdownParser.parse(md).blocks

    @Test fun headingsParagraphsAndSoftBreaks() {
        val b = blocks("## Features\n\nfirst line\nsecond line\n\n### Fixes")
        assertEquals(MdBlock.Heading(2, listOf(MdSpan("Features"))), b[0])
        assertEquals(MdBlock.Paragraph(listOf(MdSpan("first line second line"))), b[1])
        assertEquals(MdBlock.Heading(3, listOf(MdSpan("Fixes"))), b[2])
    }

    @Test fun nestedBulletsOrderedAndTaskItems() {
        val b = blocks("- one\n  - nested\n1. first\n- [x] done\n- [ ] todo")
        assertEquals(listOf(0, 1, 0, 0, 0), b.map { (it as MdBlock.Item).depth })
        assertEquals(listOf("•", "•", "1.", "☑", "☐"), b.map { (it as MdBlock.Item).marker })
    }

    @Test fun indentedLineContinuesTheListItem() {
        val item = blocks("- site: Add a landing page\n  by @someone")[0] as MdBlock.Item
        assertEquals("site: Add a landing page by @someone", item.spans.joinToString("") { it.text })
    }

    @Test fun inlineStylesCodeAndLinks() {
        val spans = MarkdownParser.parseInline("**bold** and *it* with `code` and [a link](https://x.y/z) (https://a.b/c).")
        assertEquals(MdSpan("bold", bold = true), spans[0])
        assertEquals(MdSpan("it", italic = true), spans[2])
        assertEquals(MdSpan("code", code = true), spans[4])
        assertEquals(MdSpan("a link", url = "https://x.y/z"), spans[6])
        // A bare URL becomes a link and the closing parenthesis / full stop stay outside it.
        val bare = spans.single { it.url == "https://a.b/c" }
        assertEquals("https://a.b/c", bare.text)
        assertEquals(").", spans.last().text)
    }

    @Test fun snakeCaseAndEscapesStayPlain() {
        assertEquals(listOf(MdSpan("snake_case_name and *literal*")), MarkdownParser.parseInline("snake_case_name and \\*literal\\*"))
    }

    @Test fun fencedCodeAndRules() {
        val b = blocks("before\n\n```\nval a = 1\n  val b = 2\n```\n\n---\nafter")
        assertEquals(MdBlock.Code("val a = 1\n  val b = 2"), b[1])
        assertEquals(MdBlock.Rule, b[2])
        assertTrue(b.last() is MdBlock.Paragraph)
    }

    @Test fun emptyAndBlankInput() {
        assertTrue(MarkdownParser.parse("").isEmpty)
        assertTrue(MarkdownParser.parse("\n  \n").isEmpty)
    }

    @Test fun realReleaseNoteLine() {
        val item = blocks("- ui: Add Afterglow theme ([4e004d9](https://github.com/o/r/commit/4e004d9)) by @yurtemre7")[0] as MdBlock.Item
        val link = item.spans.single { it.url != null }
        assertEquals("4e004d9", link.text)
        assertEquals("https://github.com/o/r/commit/4e004d9", link.url)
    }
}
