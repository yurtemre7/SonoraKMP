package de.yurtemre.sonora.ui.screens.weekly

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.library.WeeklyMixRepository
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.SongRow
import de.yurtemre.sonora.ui.components.displayTitle
import de.yurtemre.sonora.ui.components.rememberQueueSwipeActions
import de.yurtemre.sonora.ui.format.countdownText
import de.yurtemre.sonora.ui.format.DateFormats
import de.yurtemre.sonora.ui.format.currentLocale
import de.yurtemre.sonora.ui.navigation.albumKeyOf
import de.yurtemre.sonora.ui.navigation.artistKeyOf
import de.yurtemre.sonora.ui.sheets.SongActionsHost
import de.yurtemre.sonora.ui.sheets.rememberSongActionsState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant

/** The weekly mix: play, shuffle, add to queue, save as playlist, and the songs with the usual actions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WeeklyMixRoute(onBack: () -> Unit, onNavigate: (NavKey) -> Unit = {}) {
    val actions = rememberSongActionsState()
    val playback: PlaybackConnection = koinInject()
    val library: LibraryRepository = koinInject()
    val repo: WeeklyMixRepository = koinInject()
    val clock: Clock = koinInject()
    val vm = viewModel { WeeklyMixViewModel(repo, library.songs.map { it.isNotEmpty() }, clock) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) { vm.onResume(); onPauseOrDispose { } }
    val player by playback.player.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    val locale = currentLocale()
    val title = stringResource(R.string.weekly_mix_title)

    var saving by remember { mutableStateOf(false) }
    fun save() = scope.launch {
        if (saving) return@launch
        saving = true
        try {
        val date = DateFormats.shortDate(Instant.ofEpochMilli(ui.weekStartMs), locale)
        val name = res.getString(R.string.weekly_mix_playlist_name, date)
        val message = if (vm.save(name) != null) res.getString(R.string.weekly_mix_saved, name)
        else res.getString(R.string.weekly_mix_save_failed)
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message = message, duration = SnackbarDuration.Short)
        } finally { saving = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            !ui.loaded -> Box(Modifier.fillMaxSize().padding(padding))
            ui.songs.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(icon = Icons.Rounded.AutoAwesome, title = title, subtitle = stringResource(R.string.weekly_mix_empty))
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item(key = "header") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            countdownText(context, ui.countdown),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { playback.playSong(ui.songs.first(), ui.songs) }, Modifier.weight(1f)) {
                                Icon(Icons.Rounded.PlayArrow, null)
                                Text(stringResource(R.string.weekly_mix_play), Modifier.padding(start = 8.dp))
                            }
                            OutlinedButton(onClick = { playback.playShuffled(ui.songs) }, Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Shuffle, null)
                                Text(stringResource(R.string.weekly_mix_shuffle), Modifier.padding(start = 8.dp))
                            }
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                playback.addToQueue(ui.songs)
                                scope.launch {
                                    snackbar.currentSnackbarData?.dismiss()
                                    snackbar.showSnackbar(
                                        res.getQuantityString(R.plurals.weekly_mix_added_to_queue, ui.songs.size, ui.songs.size),
                                        duration = SnackbarDuration.Short,
                                    )
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Rounded.QueueMusic, null)
                                Text(stringResource(R.string.weekly_mix_add_to_queue), Modifier.padding(start = 8.dp))
                            }
                            TextButton(onClick = { save() }, enabled = !saving) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null)
                                Text(stringResource(R.string.weekly_mix_save), Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                items(ui.songs, key = { it.id }) { song ->
                    SongRow(
                        title = song.displayTitle(),
                        artist = song.artist,
                        durationMs = song.durationMs,
                        artwork = song.artworkPath,
                        showArtwork = true,
                        isPlaying = player.currentSong?.id == song.id,
                        onClick = { playback.playSong(song, ui.songs) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.show(song)
                        },
                        onMoreClick = { actions.show(song) },
                        swipeActions = rememberQueueSwipeActions(song, snackbar),
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
    SongActionsHost(
        state = actions,
        snackbarHostState = snackbar,
        onGoToAlbum = { onNavigate(albumKeyOf(it)) },
        onGoToArtist = { onNavigate(artistKeyOf(it)) },
    )
}
