package de.yurtemre.sonora.ui.screens.changelog

import androidx.compose.runtime.Immutable

/** An inline run of text with its style; [url] makes it a link. */
@Immutable
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val url: String? = null,
)

/** Block-level markdown element. Plain immutable data, so the renderer can skip unchanged blocks. */
sealed interface MdBlock {
    @Immutable data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    @Immutable data class Paragraph(val spans: List<MdSpan>) : MdBlock
    /** [marker] is the bullet or number shown in front; [depth] is the nesting level (0 = top). */
    @Immutable data class Item(val depth: Int, val marker: String, val spans: List<MdSpan>) : MdBlock
    @Immutable data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

/** A parsed document; the wrapper is what makes a list of blocks stable for Compose. */
@Immutable
data class MdDocument(val blocks: List<MdBlock>) {
    val isEmpty: Boolean get() = blocks.isEmpty()
}

/**
 * A small markdown reader for release notes and the changelog: headings, bullet / numbered / task lists (nested by
 * indentation), paragraphs, fenced code, rules, and `**bold**`, `*italic*`, `~~strike~~`, `` `code` ``,
 * `[links](url)` and bare URLs inline.
 *
 * It exists because the notes are shown in dialogs and screens that recompose (download progress, ticks): a full
 * markdown renderer rebuilt and re-laid out its whole tree each time. This parses once into immutable data and the
 * renderer draws plain text, so nothing here depends on composition.
 */
object MarkdownParser {
    fun parse(source: String): MdDocument {
        val blocks = ArrayList<MdBlock>()
        val paragraph = ArrayList<String>()
        var fence: ArrayList<String>? = null
        var lastWasItem = false

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(parseInline(paragraph.joinToString(" ")))
                paragraph.clear()
            }
            lastWasItem = false
        }

        for (raw in source.replace("\r\n", "\n").lines()) {
            val open = fence
            if (open != null) {
                if (raw.trimStart().startsWith("```")) {
                    blocks += MdBlock.Code(open.joinToString("\n"))
                    fence = null
                } else {
                    open += raw
                }
                continue
            }
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> { flushParagraph(); fence = ArrayList() }
                trimmed.isEmpty() -> flushParagraph()
                RULE.matches(trimmed) -> { flushParagraph(); blocks += MdBlock.Rule }
                else -> {
                    val heading = HEADING.matchEntire(trimmed)
                    val bullet = BULLET.matchEntire(line)
                    val ordered = ORDERED.matchEntire(line)
                    when {
                        heading != null -> {
                            flushParagraph()
                            val text = heading.groupValues[2].trimEnd().trimEnd('#').trimEnd()
                            blocks += MdBlock.Heading(heading.groupValues[1].length, parseInline(text))
                        }
                        bullet != null -> {
                            flushParagraph()
                            var body = bullet.groupValues[3]
                            var marker = "•"
                            TASK.matchEntire(body)?.let { marker = if (it.groupValues[1].isBlank()) "☐" else "☑"; body = it.groupValues[2] }
                            blocks += MdBlock.Item(depth(bullet.groupValues[1]), marker, parseInline(body))
                            lastWasItem = true
                        }
                        ordered != null -> {
                            flushParagraph()
                            blocks += MdBlock.Item(depth(ordered.groupValues[1]), ordered.groupValues[2] + ".", parseInline(ordered.groupValues[3]))
                            lastWasItem = true
                        }
                        // An indented line straight after a list item continues that item.
                        lastWasItem && paragraph.isEmpty() && line.startsWith("  ") -> {
                            val item = blocks.last() as MdBlock.Item
                            blocks[blocks.lastIndex] = item.copy(spans = item.spans + MdSpan(" ") + parseInline(trimmed))
                        }
                        else -> {
                            lastWasItem = false
                            paragraph += trimmed.removePrefix(">").trimStart()
                        }
                    }
                }
            }
        }
        fence?.let { blocks += MdBlock.Code(it.joinToString("\n")) }
        flushParagraph()
        return MdDocument(blocks)
    }

    private fun depth(indent: String) = indent.sumOf { if (it == '\t') 2 else 1 } / 2

    private val RULE = Regex("""(-{3,}|\*{3,}|_{3,})""")
    private val HEADING = Regex("""(#{1,6})\s+(.*)""")
    private val BULLET = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val ORDERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val TASK = Regex("""\[([ xX])]\s+(.*)""")

    private data class Flags(val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false, val url: String? = null)

    fun parseInline(text: String): List<MdSpan> = parseInline(text, Flags())

    private fun parseInline(s: String, f: Flags): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                out += MdSpan(sb.toString(), f.bold, f.italic, false, f.strike, f.url)
                sb.setLength(0)
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> { sb.append(s[i + 1]); i += 2 }
                c == '`' -> {
                    var ticks = 0
                    while (i + ticks < s.length && s[i + ticks] == '`') ticks++
                    val fence = "`".repeat(ticks)
                    val end = s.indexOf(fence, i + ticks)
                    if (end < 0) { sb.append(fence); i += ticks } else {
                        flush()
                        out += MdSpan(s.substring(i + ticks, end).trim(), f.bold, f.italic, true, f.strike, f.url)
                        i = end + ticks
                    }
                }
                s.startsWith("**", i) || s.startsWith("__", i) -> {
                    val d = s.substring(i, i + 2)
                    val end = s.indexOf(d, i + 2)
                    if (end > i + 2) { flush(); out += parseInline(s.substring(i + 2, end), f.copy(bold = true)); i = end + 2 }
                    else { sb.append(c); i++ }
                }
                s.startsWith("~~", i) -> {
                    val end = s.indexOf("~~", i + 2)
                    if (end > i + 2) { flush(); out += parseInline(s.substring(i + 2, end), f.copy(strike = true)); i = end + 2 }
                    else { sb.append(c); i++ }
                }
                (c == '*' || (c == '_' && (i == 0 || !s[i - 1].isLetterOrDigit()))) && i + 1 < s.length && !s[i + 1].isWhitespace() -> {
                    val end = s.indexOf(c, i + 1)
                    val closes = end > i + 1 && !s[end - 1].isWhitespace() && (c == '*' || end + 1 >= s.length || !s[end + 1].isLetterOrDigit())
                    if (closes) { flush(); out += parseInline(s.substring(i + 1, end), f.copy(italic = true)); i = end + 1 }
                    else { sb.append(c); i++ }
                }
                c == '[' -> {
                    val close = matching(s, i, '[', ']')
                    if (close > 0 && close + 1 < s.length && s[close + 1] == '(') {
                        val urlEnd = matching(s, close + 1, '(', ')')
                        if (urlEnd > 0) {
                            val url = s.substring(close + 2, urlEnd).trim().substringBefore(' ')
                            flush()
                            out += parseInline(s.substring(i + 1, close), f.copy(url = url))
                            i = urlEnd + 1
                            continue
                        }
                    }
                    sb.append(c); i++
                }
                c == '<' && (s.startsWith("<http://", i) || s.startsWith("<https://", i)) && s.indexOf('>', i) > 0 -> {
                    val end = s.indexOf('>', i)
                    val url = s.substring(i + 1, end)
                    flush(); out += MdSpan(url, f.bold, f.italic, false, f.strike, url); i = end + 1
                }
                f.url == null && (s.startsWith("http://", i) || s.startsWith("https://", i)) -> {
                    var end = i
                    while (end < s.length && !s[end].isWhitespace() && s[end] != '<' && s[end] != ')') end++
                    while (end > i && s[end - 1] in TRAILING) end--
                    val url = s.substring(i, end)
                    flush(); out += MdSpan(url, f.bold, f.italic, false, f.strike, url); i = end
                }
                else -> { sb.append(c); i++ }
            }
        }
        flush()
        return out
    }

    /** Index of the bracket that closes the one at [start], or -1. */
    private fun matching(s: String, start: Int, open: Char, close: Char): Int {
        var level = 0
        for (j in start until s.length) {
            when (s[j]) {
                '\\' -> Unit
                open -> level++
                close -> if (--level == 0) return j
            }
        }
        return -1
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!~<>|"
    private const val TRAILING = ".,;:!?'\""
}
