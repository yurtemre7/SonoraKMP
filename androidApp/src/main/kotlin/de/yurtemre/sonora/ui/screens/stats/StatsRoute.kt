package de.yurtemre.sonora.ui.screens.stats

import androidx.compose.foundation.background
import de.yurtemre.sonora.ui.components.preferredArtistImage
import de.yurtemre.sonora.ui.components.displayTitle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.ArtistAvatar
import de.yurtemre.sonora.ui.components.ConfirmDialog
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.format.DateFormats
import de.yurtemre.sonora.ui.format.currentLocale
import de.yurtemre.sonora.ui.navigation.Album
import de.yurtemre.sonora.ui.navigation.Artist
import de.yurtemre.sonora.ui.navigation.Playlist
import de.yurtemre.sonora.ui.navigation.RecentlyPlayed
import de.yurtemre.sonora.ui.theme.SleeveColors
import de.yurtemre.sonora.ui.theme.SleeveRowShape
import de.yurtemre.sonora.ui.theme.SleeveShape
import de.yurtemre.sonora.ui.theme.tabular
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class StatsPage(val label: Int) {
    Overview(R.string.stats_tab_overview),
    Songs(R.string.stats_tab_songs),
    Albums(R.string.stats_tab_albums),
    Artists(R.string.stats_tab_artists),
    Playlists(R.string.stats_tab_playlists),
}

/**
 * STAT-05/06 Statistics in the Sleeve identity: one page per topic in a [HorizontalPager], switched by a connected
 * button group; big condensed tabular figures on flat blocks. [onNavigate] opens album/artist/playlist/recent pages.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatsRoute(onBack: () -> Unit, onNavigate: (NavKey) -> Unit = {}) {
    val library: LibraryRepository = koinInject()
    val playlists: PlaylistRepository = koinInject()
    val stats: StatsRepository = koinInject()
    val playback: PlaybackConnection = koinInject()
    val vm = viewModel { StatsViewModel(library, playlists, stats) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalResources.current
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val pager = rememberPagerState { StatsPage.entries.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back)) }
                },
                actions = {
                    if (ui.loaded && !ui.isEmpty) IconButton(onClick = { confirmReset = true }) {
                        Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.stats_reset))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            !ui.loaded -> Box(Modifier.fillMaxSize().padding(padding))
            ui.isEmpty -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.Insights,
                    title = stringResource(R.string.stats_empty_title),
                    subtitle = stringResource(R.string.stats_empty_subtitle),
                )
            }
            else -> Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    val last = StatsPage.entries.lastIndex
                    StatsPage.entries.forEachIndexed { i, page ->
                        ToggleButton(
                            checked = pager.currentPage == i,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                scope.launch { pager.animateScrollToPage(i) }
                            },
                            shapes = when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                last -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            modifier = Modifier.semantics { role = Role.Tab },
                        ) { Text(stringResource(page.label), maxLines = 1) }
                    }
                }
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }, beyondViewportPageCount = 1) { index ->
                    val bottom = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)
                    when (StatsPage.entries[index]) {
                        StatsPage.Overview -> OverviewPage(ui, bottom, onOpenRecent = { onNavigate(RecentlyPlayed) })
                        StatsPage.Songs -> RankedPage(
                            title = stringResource(R.string.stats_top_songs),
                            empty = stringResource(R.string.stats_page_empty),
                            entries = ui.topSongs.map { r ->
                                val d = ui.songDetail[r.item.id]
                                RankedEntry(
                                    key = r.item.id.toString(), title = r.item.title,
                                    subtitle = if (d != null) stringResource(
                                        R.string.stats_song_detail, r.item.artist, fmt(d.skips), fmt(d.restarts),
                                    ) else r.item.artist,
                                    plays = r.plays, art = r.item.artworkPath, artist = r.item.artist,
                                    onClick = { playback.playSong(r.item, ui.topSongs.map { it.item }) },
                                )
                            },
                            contentPadding = bottom,
                        )
                        StatsPage.Albums -> RankedPage(
                            title = stringResource(R.string.stats_top_albums),
                            empty = stringResource(R.string.stats_page_empty),
                            entries = ui.topAlbums.map { r ->
                                RankedEntry(
                                    key = r.item.key(), title = r.item.name, subtitle = r.item.artist, plays = r.plays,
                                    art = r.item.songs.firstOrNull { it.artworkPath != null }?.artworkPath, artist = r.item.artist,
                                    onClick = { onNavigate(Album(r.item.name, r.item.artist)) },
                                )
                            },
                            contentPadding = bottom,
                        )
                        StatsPage.Artists -> RankedPage(
                            title = stringResource(R.string.stats_top_artists),
                            empty = stringResource(R.string.stats_page_empty),
                            entries = ui.topArtists.map { r ->
                                RankedEntry(
                                    key = r.item.key(), title = r.item.name, subtitle = null, plays = r.plays,
                                    art = preferredArtistImage(r.item.localImagePath), isArtist = true,
                                    onClick = { onNavigate(Artist(r.item.name)) },
                                )
                            },
                            contentPadding = bottom,
                        )
                        StatsPage.Playlists -> RankedPage(
                            title = stringResource(R.string.stats_top_playlists),
                            empty = stringResource(R.string.stats_playlists_empty),
                            entries = ui.topPlaylists.map { r ->
                                val n = ui.playlistSongCounts[r.item.id] ?: 0
                                RankedEntry(
                                    key = r.item.id, title = r.item.name,
                                    subtitle = pluralStringResource(R.plurals.stats_playlist_songs, n, n),
                                    plays = r.plays, art = ui.playlistCovers[r.item.id],
                                    onClick = { onNavigate(Playlist(r.item.id)) },
                                )
                            },
                            contentPadding = bottom,
                        )
                    }
                }
            }
        }
    }

    if (confirmReset) ConfirmDialog(
        title = stringResource(R.string.stats_reset_title),
        message = stringResource(R.string.stats_reset_message),
        confirmLabel = stringResource(R.string.stats_reset_confirm),
        destructive = true,
        onConfirm = {
            confirmReset = false
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            vm.reset()
            scope.launch { snackbar.showSnackbar(context.getString(R.string.stats_reset_done)) }
        },
        onDismiss = { confirmReset = false },
    )
}

@Composable
private fun fmt(n: Int): String {
    val locale = currentLocale()
    return remember(n, locale) { NumberFormat.getIntegerInstance(locale).format(n) }
}

@Composable
private fun OverviewPage(ui: StatsUi, contentPadding: PaddingValues, onOpenRecent: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val locale = currentLocale()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Hero: complete listens on a flat Cobalt block.
        Figure(
            value = fmt(ui.completeListens),
            label = stringResource(R.string.stats_complete_listens),
            container = cs.primary, content = cs.onPrimary, hero = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val tiles = listOf(
            R.string.stats_songs_played to ui.uniqueSongs,
            R.string.stats_albums_heard to ui.albumsHeard,
            R.string.stats_artists_heard to ui.artistsHeard,
            R.string.stats_playlists_played to ui.playlistsPlayed,
            R.string.stats_shuffle_sessions to ui.shuffleSessions,
            R.string.stats_skips to ui.skips,
        )
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value) ->
                    Figure(fmt(value), stringResource(label), cs.surfaceContainerHigh, cs.onSurface, modifier = Modifier.weight(1f))
                }
            }
        }
        // Most active day: Label yellow is only a small fill with Ink text (DESIGN).
        val day = ui.mostActiveDay?.let { k ->
            DateFormats.weekdayName(if (k == 0) DayOfWeek.SUNDAY else DayOfWeek.of(k), locale)
        } ?: stringResource(R.string.stats_none)
        Column(
            Modifier.fillMaxWidth().background(cs.surfaceContainerHigh, SleeveShape).padding(16.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Text(stringResource(R.string.stats_most_active_day), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Text(
                day,
                style = MaterialTheme.typography.headlineMedium,
                color = SleeveColors.Ink,
                modifier = Modifier.background(SleeveColors.Label, SleeveRowShape).padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
        ui.firstSong?.let { song ->
            val since = ui.firstDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(it)
            }
            Row(
                Modifier.fillMaxWidth().background(cs.surfaceContainerHigh, SleeveShape).padding(16.dp)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(song.artworkPath, song.album, Modifier.size(64.dp), artist = song.artist, decorative = true)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.stats_first_song), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Text(song.displayTitle(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (since != null) Text(
                        stringResource(R.string.stats_first_song_since, since),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).background(cs.secondaryContainer, SleeveShape)
                .clickable(onClick = onOpenRecent).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.stats_recently_played), style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer)
                Text(stringResource(R.string.stats_recently_played_sub), style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = cs.onSecondaryContainer)
        }
    }
}

@Composable
private fun Figure(
    value: String,
    label: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
) {
    Column(
        modifier.background(container, SleeveShape).padding(16.dp).clearAndSetSemantics { contentDescription = "$value $label" },
    ) {
        Text(
            value,
            style = (if (hero) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displaySmall).tabular(),
            color = content, maxLines = 1,
        )
        Text(label, style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = 0.8f), maxLines = 2)
    }
}

private class RankedEntry(
    val key: String,
    val title: String,
    val subtitle: String?,
    val plays: Int,
    val art: String?,
    val artist: String? = null,
    val isArtist: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun RankedPage(title: String, empty: String, entries: List<RankedEntry>, contentPadding: PaddingValues) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "title") {
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (entries.isEmpty()) item(key = "empty") {
            Text(empty, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        }
        itemsIndexed(entries, key = { _, e -> e.key }) { i, e ->
            val rank = i + 1
            val rankDesc = stringResource(R.string.stats_rank, rank)
            val playsText = pluralStringResource(R.plurals.stats_plays, e.plays, e.plays)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .background(if (rank == 1) cs.surfaceContainerHighest else cs.surfaceContainer, SleeveShape)
                    .clickable(onClick = e.onClick)
                    .clearAndSetSemantics { contentDescription = listOfNotNull(rankDesc, e.title, e.subtitle, playsText).joinToString(", ") }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    rank.toString(),
                    style = MaterialTheme.typography.displaySmall.tabular(),
                    color = if (rank == 1) SleeveColors.Ink else cs.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 40.dp)
                        .then(if (rank == 1) Modifier.background(SleeveColors.Label, SleeveRowShape) else Modifier)
                        .padding(horizontal = 6.dp),
                )
                Spacer(Modifier.width(12.dp))
                if (e.isArtist) ArtistAvatar(e.art, e.title, Modifier.size(56.dp))
                else AlbumArt(e.art, e.title, Modifier.size(56.dp), artist = e.artist, decorative = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.subtitle != null) Text(
                        e.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(fmt(e.plays), style = MaterialTheme.typography.headlineMedium.tabular())
            }
        }
    }
}
