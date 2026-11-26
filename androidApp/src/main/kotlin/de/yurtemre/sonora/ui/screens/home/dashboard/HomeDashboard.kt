package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.library.WeeklyMixRepository
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.DevBadge
import de.yurtemre.sonora.ui.components.SectionHeader
import de.yurtemre.sonora.ui.components.SonoraInsets
import de.yurtemre.sonora.ui.components.displayTitle
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.navigation.Settings
import de.yurtemre.sonora.ui.navigation.Stats
import de.yurtemre.sonora.ui.navigation.WeeklyMix
import de.yurtemre.sonora.ui.screens.home.greetingRes
import de.yurtemre.sonora.ui.screens.home.tabs.WeeklyMixCard
import de.yurtemre.sonora.ui.screens.weekly.WeeklyMixViewModel
import de.yurtemre.sonora.ui.theme.SleeveShape
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/**
 * The Home dashboard: greeting + actions, Continue card, library tiles, Recommended / Weekly mix / Recently played
 * rows and a Library footer. Swiping up past its end (or the footer button) opens the Library.
 */
@Composable
fun HomeDashboard(
    userName: String?,
    onOpenTab: (LibraryTab) -> Unit,
    onOpenLibrary: () -> Unit,
    onNavigate: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val library = koinInject<LibraryRepository>()
    val stats = koinInject<StatsRepository>()
    val clock = koinInject<Clock>()
    val weeklyRepo = koinInject<WeeklyMixRepository>()
    val playback = koinInject<PlaybackConnection>()
    val vm = viewModel { HomeDashboardViewModel(library, stats, clock) }
    val recommended by vm.recommended.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val weeklyVm = viewModel { WeeklyMixViewModel(weeklyRepo, library.songs.map { it.isNotEmpty() }, clock) }
    val weekly by weeklyVm.ui.collectAsStateWithLifecycle()
    LifecycleResumeEffect(weeklyVm) { weeklyVm.onResume(); onPauseOrDispose { } }
    val player by playback.player.collectAsStateWithLifecycle()
    val position = playback.positionMs.collectAsStateWithLifecycle(0L)

    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val swipeUp = remember(threshold, onOpenLibrary) {
        SwipeUpToLibrary(threshold) {
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            onOpenLibrary()
        }
    }

    Box(modifier.fillMaxSize().windowInsetsPadding(SonoraInsets.horizontal).nestedScroll(swipeUp)) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "header") {
                DashboardHeader(
                    userName = userName,
                    onSettings = { onNavigate(Settings) },
                    onStats = { onNavigate(Stats) },
                    onLibrary = onOpenLibrary,
                    modifier = Modifier.windowInsetsPadding(SonoraInsets.top),
                )
            }
            val current = player.currentSong
            if (current != null && !player.isPlaying) item(key = "continue") {
                ContinueCard(
                    song = current,
                    progress = { if (player.durationMs > 0) (position.value.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f },
                    onPlay = {
                        if (player.isCompleted) playback.seekTo(0)
                        playback.play()
                    },
                )
            }
            item(key = "tiles") { HomeTiles(onOpenTab) }
            if (recommended.isNotEmpty()) {
                item(key = "recommended_header") { SectionHeader(stringResource(R.string.home_recommended)) }
                item(key = "recommended") { SongCarousel(recommended, playback) }
            }
            item(key = "weekly") { WeeklyMixCard(weekly, onOpen = { onNavigate(WeeklyMix) }, modifier = Modifier) }
            if (recent.isNotEmpty()) {
                item(key = "recent_header") { SectionHeader(stringResource(R.string.home_recent)) }
                item(key = "recent") { SongCarousel(recent, playback) }
            }
            item(key = "footer") {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    FilledTonalButton(onClick = onOpenLibrary) {
                        Icon(Icons.Rounded.LibraryMusic, null, Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.home_open_library))
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    userName: String?,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = if (userName != null) stringResource(greetingRes(), userName) else stringResource(R.string.home_title)
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.6).sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
        Spacer(Modifier.width(8.dp))
        DevBadge()
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onStats) { Icon(Icons.Rounded.Insights, stringResource(R.string.home_action_stats)) }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.home_action_settings)) }
        IconButton(onClick = onLibrary) { Icon(Icons.Rounded.LibraryMusic, stringResource(R.string.home_action_library)) }
    }
}

/** Horizontal row of song cards; tapping one plays it with the whole row as the queue. */
@Composable
private fun SongCarousel(songs: List<Song>, playback: PlaybackConnection) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(songs, key = { it.id }) { song -> SongCard(song) { playback.playSong(song, songs) } }
    }
}

@Composable
private fun SongCard(song: Song, onClick: () -> Unit) {
    Column(Modifier.width(128.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AlbumArt(song.artworkPath, song.album, Modifier.size(128.dp), shape = SleeveShape, decorative = true, artist = song.artist)
        Text(song.displayTitle(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            song.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
