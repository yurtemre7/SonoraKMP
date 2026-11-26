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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
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
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.DevBadge
import de.yurtemre.sonora.ui.components.SectionHeader
import de.yurtemre.sonora.ui.components.SonoraInsets
import de.yurtemre.sonora.ui.components.displayTitle
import de.yurtemre.sonora.ui.navigation.Album
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.navigation.Settings
import de.yurtemre.sonora.ui.navigation.Stats
import de.yurtemre.sonora.ui.navigation.WeeklyMix
import de.yurtemre.sonora.ui.screens.home.NameEditDialog
import de.yurtemre.sonora.ui.screens.home.greetingRes
import de.yurtemre.sonora.ui.screens.home.tabs.WeeklyMixCard
import de.yurtemre.sonora.ui.screens.weekly.WeeklyMixViewModel
import de.yurtemre.sonora.ui.theme.SleeveShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The Home dashboard: app-name toolbar + greeting, now-playing / Continue card, Shuffle and Scan CTAs, stats glance,
 * favourite song, library tiles, Recommended / Weekly mix / Recently played rows. Swiping up past the end opens the Library.
 */
@Composable
fun HomeDashboard(
    userName: String,
    isSyncing: Boolean,
    onScanLibrary: () -> Unit,
    onOpenTab: (LibraryTab) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onNavigate: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val library = koinInject<LibraryRepository>()
    val stats = koinInject<StatsRepository>()
    val clock = koinInject<Clock>()
    val weeklyRepo = koinInject<WeeklyMixRepository>()
    val playback = koinInject<PlaybackConnection>()
    val playlistRepo = koinInject<PlaylistRepository>()
    val vm = viewModel { HomeDashboardViewModel(library, stats, clock, playlistRepo) }
    val recommended by vm.recommended.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val glance by vm.glance.collectAsStateWithLifecycle()
    val timeMix by vm.timeMix.collectAsStateWithLifecycle()
    val jumpBack by vm.jumpBack.collectAsStateWithLifecycle()
    val hasPlaylistSongs by vm.hasPlayablePlaylist.collectAsStateWithLifecycle()
    val tasks by library.backgroundTasks.collectAsStateWithLifecycle()
    val weeklyVm = viewModel { WeeklyMixViewModel(weeklyRepo, library.songs.map { it.isNotEmpty() }, clock) }
    val weekly by weeklyVm.ui.collectAsStateWithLifecycle()
    LifecycleResumeEffect(weeklyVm) { weeklyVm.onResume(); onPauseOrDispose { } }
    val player by playback.player.collectAsStateWithLifecycle()
    val position = playback.positionMs.collectAsStateWithLifecycle(0L)
    val scope = rememberCoroutineScope()
    var nameDialog by rememberSaveable { mutableStateOf(false) }

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
                    onEditName = { nameDialog = true },
                    onSettings = { onNavigate(Settings) },
                    onStats = { onNavigate(Stats) },
                    modifier = Modifier.windowInsetsPadding(SonoraInsets.top),
                )
            }
            val current = player.currentSong
            if (current != null && player.isPlaying) item(key = "now_playing") {
                NowPlayingLyricsCard(
                    song = current,
                    positionMs = { position.value },
                    onOpen = onOpenNowPlaying,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else if (current != null) item(key = "continue") {
                ContinueCard(
                    song = current,
                    progress = { if (player.durationMs > 0) (position.value.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f },
                    onPlay = {
                        if (player.isCompleted) playback.seekTo(0)
                        playback.play()
                    },
                )
            }
            item(key = "ctas") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ShuffleAllCta(
                        enabled = (glance?.songCount ?: 0) > 0,
                        onShuffle = { scope.launch { playback.playShuffled(library.songs.first()) } },
                        modifier = Modifier.weight(1f),
                    )
                    ScanLibraryCta(
                        isSyncing = isSyncing,
                        tasks = tasks,
                        onScan = onScanLibrary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            glance?.let { g ->
                item(key = "glance") {
                    StatsGlanceCard(g, onOpenStats = { onNavigate(Stats) }, modifier = Modifier.padding(horizontal = 16.dp))
                }
                g.favorite?.let { fav ->
                    item(key = "favorite") {
                        FavoriteSongCard(
                            song = fav,
                            plays = g.favoritePlays,
                            onPlay = { playback.playSong(fav, listOf(fav)) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            item(key = "tiles") {
                HomeTiles(
                    onOpenTab,
                    quickEnabled = { tab ->
                        if (tab == LibraryTab.Playlists) hasPlaylistSongs else (glance?.songCount ?: 0) > 0
                    },
                    onQuickShuffle = { tab ->
                        scope.launch { vm.randomTarget(tab)?.let { playback.playShuffled(it.songs, it.playlistId) } }
                    },
                )
            }
            if (jumpBack.isNotEmpty()) {
                item(key = "jump_back_header") { SectionHeader(stringResource(R.string.home_jump_back_in)) }
                item(key = "jump_back") {
                    JumpBackInRow(
                        jumpBack,
                        onOpen = { onNavigate(Album(it.albumName, it.albumArtist)) },
                        onPlay = { if (it.songs.isNotEmpty()) playback.playSong(it.songs.first(), it.songs) },
                    )
                }
            }
            // Rows with no data: a short message once the library has songs; nothing at all on a fresh install
            // (the Scan CTA above already covers that) or while the first stats emission is pending.
            val hasSongs = (glance?.songCount ?: 0) > 0
            if (recommended.isNotEmpty() || hasSongs) {
                item(key = "recommended_header") { SectionHeader(stringResource(R.string.home_recommended)) }
                item(key = "recommended") {
                    if (recommended.isNotEmpty()) SongCarousel(recommended, playback)
                    else EmptyRowMessage(stringResource(R.string.home_recommended_empty))
                }
            }
            timeMix?.takeIf { it.songs.isNotEmpty() }?.let { mix ->
                item(key = "time_mix") { TimeMixCard(mix, onPlay = { playback.playSong(mix.songs.first(), mix.songs) }) }
            }
            if (weekly.loaded && weekly.songs.isNotEmpty()) {
                item(key = "weekly") { WeeklyMixCard(weekly, onOpen = { onNavigate(WeeklyMix) }, modifier = Modifier) }
            }
            if (recent.isNotEmpty() || hasSongs) {
                item(key = "recent_header") { SectionHeader(stringResource(R.string.home_recent)) }
                item(key = "recent") {
                    if (recent.isNotEmpty()) SongCarousel(recent, playback)
                    else EmptyRowMessage(stringResource(R.string.home_recent_empty))
                }
            }
            item(key = "end") { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (nameDialog) NameEditDialog(userName, onDismiss = { nameDialog = false })
}

private const val NAME_MARK = "\uE000"

@Composable
private fun DashboardHeader(
    userName: String,
    onEditName: () -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.app_name),
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
        }
        // The greeting places the name differently per language: format with a marker, split around it and link the name.
        val greeting = stringResource(greetingRes(), NAME_MARK)
        val linkStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
        val text = remember(greeting, userName, linkStyle) {
            val at = greeting.indexOf(NAME_MARK)
            buildAnnotatedString {
                if (at < 0) append(greeting)
                else {
                    append(greeting.substring(0, at))
                    withLink(LinkAnnotation.Clickable("name", linkStyle) { onEditName() }) { append(userName) }
                    append(greeting.substring(at + NAME_MARK.length))
                }
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EmptyRowMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
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
