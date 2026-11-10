package de.yurtemre.sonora.shared.library

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Custom-accent swatch bucketing (LIB-22), mirrors Flutter `HSLColor.fromColor`. */
object SeedColorBuckets {
    const val NEUTRAL = "neutral"

    /** Returns `"neutral"` or `"hue_<sector>"` (30° sectors). */
    fun bucketOf(argb: Int): String {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val delta = mx - mn
        val lightness = (mx + mn) / 2
        val saturation = if (lightness == 1.0) 0.0 else (delta / (1 - abs(2 * lightness - 1))).coerceIn(0.0, 1.0)
        if (saturation < 0.15 || lightness < 0.15 || lightness > 0.85) return NEUTRAL
        var hue = when {
            delta == 0.0 -> 0.0
            mx == r -> 60 * (((g - b) / delta) % 6)
            mx == g -> 60 * ((b - r) / delta + 2)
            else -> 60 * ((r - g) / delta + 4)
        }
        if (hue < 0) hue += 360
        val sector = ((hue / 30).roundToInt() * 30) % 360
        return "hue_$sector"
    }

    /** First color per bucket, in input order, after [leading] (e.g. Ember) which is always kept. */
    fun uniqueSwatches(seeds: List<Int>, leading: Int): List<Int> {
        val buckets = HashSet<String>()
        val out = mutableListOf(leading)
        for (c in seeds) if (buckets.add(bucketOf(c))) out += c
        return out
    }
}
