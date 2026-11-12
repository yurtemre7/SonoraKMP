package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.QueueUiState
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.ArtSize
import de.yurtemre.sonora.ui.theme.SleeveShape
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlin.math.absoluteValue

/**
 * Square sleeve (DESIGN §3, 8 dp); tap toggles immersive mode. With a [queue] the sleeve is a pager over the
 * play order: swiping left/right follows the finger with the neighbouring sleeves peeking in and skips on
 * settle (P5-25). External track changes scroll the pager to the new current item.
 */
@Composable
fun ArtworkArea(
    song: Song,
    immersive: Boolean,
    onToggleImmersive: () -> Unit,
    modifier: Modifier = Modifier,
    queue: QueueUiState? = null,
    onSkipTo: (Long) -> Unit = {},
) {
    val label = stringResource(if (immersive) R.string.np_immersive_exit else R.string.np_immersive_enter)
    val items = queue?.items.orEmpty()
    val current = queue?.currentIndex ?: -1
    if (items.isEmpty() || current !in items.indices) {
        Sleeve(song, Modifier.clickable(onClickLabel = label, onClick = onToggleImmersive), modifier)
        return
    }
    val pager = rememberPagerState(initialPage = current) { items.size }
    val haptics = LocalHapticFeedback.current
    val latestItems by rememberUpdatedState(items)
    val latestCurrent by rememberUpdatedState(current)
    val latestSkip by rememberUpdatedState(onSkipTo)
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    // Only settles caused by a finger may skip; programmatic follow-scrolls (possibly interrupted by a newer
    // skip, e.g. "next" tapped twice fast) must never feed back into the player.
    var userSwiped by remember { mutableStateOf(false) }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { if (it is DragInteraction.Start) userSwiped = true }
    }

    // Player → pager: follow skips made elsewhere (transport, notification, Up Next). A newer skip
    // interrupts a running follow animation; only an active drag is left alone.
    LaunchedEffect(current) {
        if (pager.currentPage != current && !dragged) {
            userSwiped = false
            pager.animateScrollToPage(current, animationSpec = spec)
        }
    }
    // Pager → player: a user swipe that settles on another sleeve skips to it.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { page ->
            if (!userSwiped) return@collect
            userSwiped = false
            val item = latestItems.getOrNull(page) ?: return@collect
            if (page != latestCurrent) {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                latestSkip(item.entryUid)
            }
        }
    }
    val prev = stringResource(R.string.np_previous)
    val next = stringResource(R.string.np_next)
    HorizontalPager(
        state = pager,
        modifier = modifier.fillMaxWidth().aspectRatio(1f).semantics {
            customActions = buildList {
                items.getOrNull(current - 1)?.let { add(CustomAccessibilityAction(prev) { latestSkip(it.entryUid); true }) }
                items.getOrNull(current + 1)?.let { add(CustomAccessibilityAction(next) { latestSkip(it.entryUid); true }) }
            }
        },
        pageSpacing = 16.dp,
        beyondViewportPageCount = 1,
        key = { items[it].entryUid },
    ) { page ->
        Sleeve(
            items[page].song,
            Modifier.clickable(onClickLabel = label, onClick = onToggleImmersive),
            Modifier.graphicsLayer {
                val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                val s = lerp(1f, 0.86f, offset)
                scaleX = s
                scaleY = s
                alpha = lerp(1f, 0.6f, offset)
            },
        )
    }
}

@Composable
private fun Sleeve(song: Song, click: Modifier, modifier: Modifier) {
    AlbumArt(
        model = song.artworkPath,
        title = song.album,
        artist = song.artist,
        size = ArtSize.Large,
        shape = SleeveShape,
        modifier = modifier.fillMaxWidth().aspectRatio(1f).then(click),
    )
}
