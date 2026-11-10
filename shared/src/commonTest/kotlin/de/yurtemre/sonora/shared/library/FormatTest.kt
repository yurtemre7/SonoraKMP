package de.yurtemre.sonora.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test fun displayTitleRules() {
        val t = "Cosmos - Starlight (feat. Stellar)"
        assertEquals("Starlight", TitleFormatter.displayTitle(t, "Cosmos", true, true))
        assertEquals("Cosmos - Starlight", TitleFormatter.displayTitle(t, "Cosmos", true, false))
        assertEquals(t, TitleFormatter.displayTitle(" $t ", "Cosmos", false, false))
        assertEquals("Song feat. X", TitleFormatter.displayTitle("Song feat. X", "", true, true))
        assertEquals("Cosmos", TitleFormatter.displayTitle("Cosmos", "Cosmos", false, true))
    }

    @Test fun totalDurationParts() {
        assertEquals(DurationParts(null, 1, 0, 0), DurationFormat.totalParts(3_600_000))
        assertEquals(DurationParts(null, 1, 0, 5), DurationFormat.totalParts(3_605_000))
        assertEquals(DurationParts(null, null, 1, 30), DurationFormat.totalParts(90_000))
        assertEquals(DurationParts(null, null, 0, 5), DurationFormat.totalParts(5_000))
        assertEquals(DurationParts(1, 2, 3, 4), DurationFormat.totalParts((93_784L) * 1000))
        assertEquals(DurationParts(1, null, 5, 0), DurationFormat.totalParts((86_400L + 300) * 1000))
        assertEquals(DurationParts(null, null, 0, 0), DurationFormat.totalParts(emptyList()))
    }

    @Test fun songAndPositionFormats() {
        assertEquals("65:05", DurationFormat.songDuration(3_905_000))
        assertEquals("1:05:05", DurationFormat.position(3_905_000))
        assertEquals("3:07", DurationFormat.position(187_000))
    }

    @Test fun colorBuckets() {
        assertEquals("hue_0", SeedColorBuckets.bucketOf(0xFFFF0000.toInt()))
        assertEquals("hue_120", SeedColorBuckets.bucketOf(0xFF00FF00.toInt()))
        assertEquals("hue_0", SeedColorBuckets.bucketOf(0xFFFF0010.toInt())) // hue ~356 → 360 % 360
        assertEquals("neutral", SeedColorBuckets.bucketOf(0xFF808080.toInt()))
        assertEquals("neutral", SeedColorBuckets.bucketOf(0xFF0A0000.toInt()))
        val ember = 0xFF7C4DFF.toInt()
        assertEquals(listOf(ember, 0xFFFF0000.toInt()), SeedColorBuckets.uniqueSwatches(listOf(0xFFFF0000.toInt(), 0xFFEE0000.toInt()), ember))
    }
}
