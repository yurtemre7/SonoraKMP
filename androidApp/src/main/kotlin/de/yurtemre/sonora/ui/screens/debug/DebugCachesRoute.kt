package de.yurtemre.sonora.ui.screens.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.BuildConfig
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.format.DateFormats
import de.yurtemre.sonora.ui.format.currentLocale
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

private data class DirStat(val label: Int, val files: Int, val bytes: Long)

private class DebugSnapshot(
    val songs: Int, val playlists: Int, val favSongs: Int, val favAlbums: Int, val favArtists: Int, val statSongs: Int,
    val dirs: List<DirStat>, val settings: AppSettings,
)

private fun dirStat(label: Int, dir: File): DirStat {
    var files = 0
    var bytes = 0L
    dir.walkTopDown().filter { it.isFile }.forEach { files++; bytes += it.length() }
    return DirStat(label, files, bytes)
}

/** Debug caches (P5-21): read-only DB counts, artwork cache sizes, last sync and a settings dump. Debug builds only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugCachesRoute(onBack: () -> Unit) {
    if (!BuildConfig.DEBUG) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    BackHandler(onBack = onBack)
    val library: LibraryRepository = koinInject()
    val playlists: PlaylistRepository = koinInject()
    val favorites: FavoritesRepository = koinInject()
    val stats: StatsRepository = koinInject()
    val settingsRepo: SettingsRepository = koinInject()
    val context = LocalContext.current
    val snapshot by produceState<DebugSnapshot?>(null) {
        value = withContext(Dispatchers.IO) {
            DebugSnapshot(
                songs = library.songs.first().size,
                playlists = playlists.playlists.first().size,
                favSongs = favorites.favoriteSongs.first().size,
                favAlbums = favorites.favoriteAlbums.first().size,
                favArtists = favorites.favoriteArtists.first().size,
                statSongs = stats.snapshot.first().songs.size,
                dirs = listOf(
                    dirStat(R.string.debug_dir_album_art, File(context.cacheDir, "album_art")),
                    dirStat(R.string.debug_dir_embedded_art, File(context.filesDir, "embedded_art")),
                    dirStat(R.string.debug_dir_artist_images, File(context.filesDir, "artist_images")),
                    dirStat(R.string.debug_dir_cache_total, context.cacheDir),
                ),
                settings = settingsRepo.settings.first(),
            )
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_title_debug_caches)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back)) }
                },
            )
        },
    ) { padding ->
        val s = snapshot ?: return@Scaffold
        val settingLines = s.settings.toString().substringAfter('(').removeSuffix(")").split(", ")
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Header(R.string.debug_section_database) }
            item { Row(R.string.debug_songs, s.songs.toString()) }
            item { Row(R.string.debug_playlists, s.playlists.toString()) }
            item { Row(R.string.debug_favorites, "${s.favSongs} / ${s.favAlbums} / ${s.favArtists}") }
            item { Row(R.string.debug_stats_songs, s.statSongs.toString()) }
            item { Header(R.string.debug_section_artwork) }
            items(s.dirs, key = { it.label }) { d ->
                Row(d.label, stringResource(R.string.debug_dir_value, d.files, android.text.format.Formatter.formatShortFileSize(context, d.bytes)))
            }
            item { Header(R.string.debug_section_sync) }
            item { Row(R.string.debug_last_sync_duration, s.settings.lastSyncDurationMs?.let { stringResource(R.string.debug_ms, it) } ?: "—") }
            item { Row(R.string.debug_last_sync_at, s.settings.lastSyncTimestampMs?.let { DateFormats.dateTime(java.time.Instant.ofEpochMilli(it), currentLocale()) } ?: "—") }
            item { Header(R.string.debug_section_settings) }
            items(settingLines, key = { it }) { line ->
                SelectionContainer {
                    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun Header(label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Row(label: Int, value: String) {
    ListItem(headlineContent = { Text(stringResource(label)) }, trailingContent = { Text(value) })
}
