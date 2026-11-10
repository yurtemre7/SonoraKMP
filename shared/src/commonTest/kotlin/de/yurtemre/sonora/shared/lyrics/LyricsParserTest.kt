package de.yurtemre.sonora.shared.lyrics

import de.yurtemre.sonora.shared.model.LyricLine
import kotlin.test.*

class LyricsParserTest {
    @Test fun decimalFractions() {
        val l = LyricsParser.parse("[00:01.5]a\n[00:02.05]b\n[00:03.123]c\n[01:00]d")!!
        assertTrue(l.isSynchronized)
        assertEquals(listOf(1500L, 2050L, 3123L, 60000L), l.lines.map { it.timeMs })
    }
    @Test fun multiStampSortedAndEmptyDropped() {
        val l = LyricsParser.parse("[ti:Song]\n[00:10.00][00:02.00]chorus\n[00:05.00]\n[00:04.00]verse")!!
        assertEquals(listOf(LyricLine(2000, "chorus"), LyricLine(4000, "verse"), LyricLine(10000, "chorus")), l.lines)
    }
    @Test fun offsetApplied() {
        val l = LyricsParser.parse("[offset:+500]\n[00:02.00]x\n[00:00.20]y")!!
        assertEquals(listOf(0L, 1500L), l.lines.map { it.timeMs })
        assertEquals(listOf(3000L), LyricsParser.parse("[offset:-1000]\n[00:02.00]x")!!.lines.map { it.timeMs })
    }
    @Test fun plainKeepsSectionHeaders() {
        val l = LyricsParser.parse("[ar:Someone]\n\n[Verse 1]\n Hello \nWorld")!!
        assertFalse(l.isSynchronized)
        assertEquals(listOf("[Verse 1]", "Hello", "World"), l.lines.map { it.text })
        assertTrue(l.lines.all { it.timeMs == 0L })
        assertNull(LyricsParser.parse("  \n"))
    }
    @Test fun sidecars() {
        assertEquals(listOf("/m/a.b.lrc", "/m/a.b.txt"), LyricsParser.sidecarPaths("/m/a.b.mp3"))
        assertEquals(emptyList(), LyricsParser.sidecarPaths("/m.d/noext"))
    }
}
