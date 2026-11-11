package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.library.ScrollSection
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.model.SongSort
import de.yurtemre.sonora.ui.theme.SleeveColors
import de.yurtemre.sonora.ui.theme.tabular

/** Key of one record-bin divider (DESIGN §6b.1). Letters / minutes / plays come from the shared [ScrollSection]. */
@Immutable
sealed interface BinKey {
    data class Section(val section: ScrollSection) : BinKey
    /** 0 = this week, 1 = this month, 2 = earlier. */
    data class Added(val bucket: Int) : BinKey
    /** Count band [from]..[to] (`to == null` → open-ended). [plays] picks the "plays" wording. */
    data class Band(val from: Int, val to: Int?, val plays: Boolean = false) : BinKey
}

/** A run of consecutive items sharing one [key]. */
@Immutable
data class Bin<T>(val key: BinKey, val items: List<T>)

/** Groups consecutive items with the same key (works for either sort direction). */
fun <T> binsOf(items: List<T>, keyOf: (T) -> BinKey): List<Bin<T>> {
    val out = ArrayList<Bin<T>>()
    var key: BinKey? = null
    var run = ArrayList<T>()
    for (item in items) {
        val k = keyOf(item)
        if (k != key && run.isNotEmpty()) { out += Bin(key!!, run); run = ArrayList() }
        key = k
        run += item
    }
    if (run.isNotEmpty()) out += Bin(key!!, run)
    return out
}

private val CountBands = intArrayOf(1, 5, 10, 25, 50, 100)

/** Band for a count: 0, 1–4, 5–9, 10–24, 25–49, 50–99, 100+. */
fun countBand(n: Int, plays: Boolean = false): BinKey.Band {
    if (n <= 0) return BinKey.Band(0, 0, plays)
    val i = CountBands.indexOfLast { n >= it }
    val to = CountBands.getOrNull(i + 1)?.minus(1)
    return BinKey.Band(CountBands[i], to, plays)
}

private const val DAY_MS = 86_400_000L

/** "This week / This month / Earlier" bucket for a date-added timestamp. */
fun addedBin(dateAddedMs: Long?, now: Long = System.currentTimeMillis()): BinKey.Added {
    val age = now - (dateAddedMs ?: 0L)
    return BinKey.Added(if (age < 7 * DAY_MS) 0 else if (age < 30 * DAY_MS) 1 else 2)
}

/** Divider key for a song row under [sort]. Duration uses two-minute ranges. */
fun songBin(song: Song, sort: SongSort, plays: (Song) -> Int): BinKey = when (sort) {
    SongSort.Recent -> addedBin(song.dateAddedMs ?: song.lastModifiedMs)
    SongSort.Plays -> countBand(plays(song), plays = true)
    SongSort.Duration -> {
        val m = (song.durationMs / 60_000).toInt()
        if (m >= 10) BinKey.Band(10, null) else (m / 2 * 2).let { BinKey.Band(it, it + 2) }
    }
    else -> BinKey.Section(ScrollSection.forSong(song, sort))
}

fun letterBin(text: String): BinKey = BinKey.Section(ScrollSection.letterOf(text))

/** Localized divider text. */
@Composable
fun binLabel(key: BinKey): String = when (key) {
    is BinKey.Section -> scrollSectionLabel(key.section)
    is BinKey.Added -> stringResource(
        when (key.bucket) { 0 -> R.string.home2_bin_this_week; 1 -> R.string.home2_bin_this_month; else -> R.string.home2_bin_earlier },
    )
    is BinKey.Band -> when {
        key.plays && key.to == 0 -> stringResource(R.string.home2_bin_not_played)
        key.plays && key.to == null -> stringResource(R.string.home2_bin_plays_open, key.from)
        key.plays -> stringResource(R.string.home2_bin_plays, key.from, key.to!!)
        key.from == 10 && key.to == null -> stringResource(R.string.home2_bin_minutes_open, key.from)
        key.to == key.from + 2 -> stringResource(R.string.home2_bin_minutes, key.from, key.to!!)
        key.to == null -> stringResource(R.string.home2_bin_range_open, key.from)
        else -> stringResource(R.string.home2_bin_range, key.from, key.to)
    }
}

private val TabShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)

/**
 * Record-bin divider (DESIGN §6b.1): a Label-yellow tab with Ink text standing on a Label rule,
 * like the cards between sections of a record-store bin. Opaque, so it works as a sticky header.
 */
@Composable
fun BinDivider(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 8.dp)
            .semantics(mergeDescendants = true) { heading() },
    ) {
        Box(
            Modifier
                .padding(start = 16.dp)
                .widthIn(min = 40.dp)
                .background(SleeveColors.Label, TabShape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                label,
                color = SleeveColors.Ink,
                style = MaterialTheme.typography.titleSmall.tabular(),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 16.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(SleeveColors.Label))
    }
}

/** Lazy index of each bin's divider, given [leading] items before the first divider. */
fun binStarts(bins: List<Bin<*>>, leading: Int = 0): List<Int> {
    var i = leading
    return bins.map { b -> i.also { i += 1 + b.items.size } }
}
