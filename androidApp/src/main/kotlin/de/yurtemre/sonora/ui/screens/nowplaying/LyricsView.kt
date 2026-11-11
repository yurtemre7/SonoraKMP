package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.Lyrics
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.scan.LyricsLocator
import de.yurtemre.sonora.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private sealed interface LyricsLoad {
    data object Loading : LyricsLoad
    data object None : LyricsLoad
    data class Loaded(val lyrics: Lyrics) : LyricsLoad
}

private const val MANUAL_SCROLL_PAUSE_MS = 4_000L

/**
 * Lyrics (UI-31, LIB-24): synced .lrc auto-scrolls with the active line centred (Cobalt, emphasized),
 * tap a line to seek, a manual scroll pauses auto-scroll for 4 s; plain .txt scrolls freely.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsView(song: Song, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val load by produceState<LyricsLoad>(LyricsLoad.Loading, song.id, song.filePath) {
        value = withContext(Dispatchers.IO) { LyricsLocator.load(song.filePath) }
            ?.let { LyricsLoad.Loaded(it) } ?: LyricsLoad.None
    }
    when (val l = load) {
        LyricsLoad.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        LyricsLoad.None -> EmptyState(
            icon = Icons.Outlined.Lyrics,
            title = stringResource(R.string.lyrics_empty_title),
            subtitle = stringResource(R.string.lyrics_empty_subtitle),
            modifier = modifier.fillMaxSize(),
        )
        is LyricsLoad.Loaded -> if (l.lyrics.isSynchronized) SyncedLyrics(l.lyrics, positionMs, onSeek, modifier)
        else PlainLyrics(l.lyrics, modifier)
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    val lines = lyrics.lines
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val active = remember(lines, positionMs) { lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0) }
    var autoScrolling by remember { mutableStateOf(false) }
    var pausedUntil by remember { mutableLongStateOf(0L) }

    // Any user-driven scroll pauses auto-scroll for 4 s.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) pausedUntil = System.currentTimeMillis() + MANUAL_SCROLL_PAUSE_MS
        }
    }
    LaunchedEffect(active, pausedUntil) {
        val wait = pausedUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        val info = listState.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        val itemSize = info.visibleItemsInfo.firstOrNull { it.index == active }?.size ?: 0
        autoScrolling = true
        try {
            listState.animateScrollToItem(active, -(viewport / 2 - itemSize / 2))
        } finally {
            autoScrolling = false
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 160.dp),
    ) {
        itemsIndexed(lines, key = { i, line -> "$i-${line.timeMs}" }) { i, line ->
            val isActive = i == active
            val color by animateColorAsState(
                if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                MaterialTheme.motionScheme.fastEffectsSpec(), label = "lyricColor",
            )
            val scale by animateFloatAsState(
                if (isActive) 1.06f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "lyricScale",
            )
            val seekLabel = stringResource(R.string.lyrics_line_seek)
            Text(
                text = line.text,
                color = color,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        pausedUntil = 0L
                        onSeek(line.timeMs)
                    }
                    .semantics { onClick(label = seekLabel) { onSeek(line.timeMs); true } }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        itemsIndexed(lyrics.lines, key = { i, _ -> i }) { _, line ->
            Text(line.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}
