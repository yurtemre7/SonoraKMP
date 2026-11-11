package de.yurtemre.sonora.ui.screens.favorites

import de.yurtemre.sonora.ui.navigation.artistKeyOf
import de.yurtemre.sonora.ui.navigation.albumKeyOf
import de.yurtemre.sonora.ui.sheets.rememberSongActionsState
import de.yurtemre.sonora.ui.sheets.SongActionsHost
import de.yurtemre.sonora.ui.sheets.PlaylistSelectorSheetHost
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.R
import de.yurtemre.sonora.platform.ShareHelper
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.ArtSize
import de.yurtemre.sonora.ui.components.ArtistAvatar
import de.yurtemre.sonora.ui.components.EmptyState
import de.yurtemre.sonora.ui.components.MultiSelectActionBar
import de.yurtemre.sonora.ui.components.SectionHeader
import de.yurtemre.sonora.ui.components.SongRow
import de.yurtemre.sonora.ui.components.SongRowAction
import de.yurtemre.sonora.ui.components.rememberSelectionState
import de.yurtemre.sonora.ui.format.rememberTotalDurationText
import de.yurtemre.sonora.ui.navigation.Album
import de.yurtemre.sonora.ui.navigation.Artist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * UI-26 Favorites. Albums are a genuine M3 multi-browse carousel the user drives (no auto-scroll); songs swipe away
 * with undo; albums/artists unfavourite on long-press with undo. [onNavigate] opens album/artist pages.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FavoritesRoute(onBack: () -> Unit, onNavigate: (NavKey) -> Unit = {}) {
    val actions = rememberSongActionsState()
    var addToPlaylistIds by remember { mutableStateOf<List<Long>?>(null) }
    val playback: PlaybackConnection = koinInject()
    val library: LibraryRepository = koinInject()
    val favorites: FavoritesRepository = koinInject()
    val vm = viewModel { FavoritesViewModel(library, favorites) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by playback.player.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalResources.current
    val selection = rememberSelectionState()
    var showSort by rememberSaveable { mutableStateOf(false) }

    fun removeWithUndo(key: String, label: String, song: Song? = null) {
        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        vm.hide(key)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = try {
                snackbar.showSnackbar(
                    context.getString(R.string.fav_removed, label), context.getString(R.string.fav_undo),
                    duration = SnackbarDuration.Short,
                )
            } catch (e: CancellationException) { SnackbarResult.Dismissed }
            if (result == SnackbarResult.ActionPerformed) vm.undo(key) else vm.commit(key, song)
        }
    }

    BackHandler(enabled = selection.active) { selection.clear() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fav_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back)) }
                },
                actions = {
                    if (ui.songs.size > 1) IconButton(onClick = { showSort = true }) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.fav_sort))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selection.active) {
                fun pick(ids: Set<Long>) = ui.songs.filter { it.id in ids }
                MultiSelectActionBar(
                    selection = selection,
                    allIds = { ui.songs.map { it.id } },
                    snackbarHostState = snackbar,
                    onPlayNext = { playback.playNext(pick(it)) },
                    onAddToQueue = { playback.addToQueue(pick(it)) },
                    onAddToPlaylist = { addToPlaylistIds = it.toList() },
                    allFavorite = true,
                    onFavoriteToggle = { ids, fav -> vm.favoriteBulk(pick(ids), fav) },
                    shareItems = { ids -> pick(ids).map { ShareHelper.Item(it.filePath, it.id, it.format?.let { f -> "audio/$f" }) } },
                )
            }
        },
    ) { padding ->
        when {
            !ui.loaded -> Box(Modifier.fillMaxSize().padding(padding))
            ui.isEmpty -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.FavoriteBorder,
                    title = stringResource(R.string.fav_empty_title),
                    subtitle = stringResource(R.string.fav_empty_subtitle),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                if (ui.albums.isNotEmpty()) {
                    item(key = "h_albums") { SectionHeader(stringResource(R.string.fav_section_albums), count = ui.albums.size) }
                    item(key = "albums") {
                        AlbumCarousel(
                            ui.albums,
                            onOpen = { onNavigate(Album(it.name, it.artist)) },
                            onRemove = { removeWithUndo(FavoritesViewModel.albumKey(it.key()), it.name) },
                        )
                    }
                }
                if (ui.artists.isNotEmpty()) {
                    item(key = "h_artists") { SectionHeader(stringResource(R.string.fav_section_artists), count = ui.artists.size) }
                    item(key = "artists") {
                        ArtistStrip(
                            ui.artists,
                            onOpen = { onNavigate(Artist(it.name)) },
                            onRemove = { removeWithUndo(FavoritesViewModel.artistKey(it.key()), it.name) },
                        )
                    }
                }
                if (ui.songs.isNotEmpty()) {
                    item(key = "h_songs") {
                        val total = rememberTotalDurationText(ui.songs.sumOf { it.durationMs } / 1000)
                        val count = pluralStringResource(R.plurals.fav_song_count, ui.songs.size, ui.songs.size)
                        Column {
                            SectionHeader(stringResource(R.string.fav_section_songs))
                            Text(
                                stringResource(R.string.fav_songs_summary, count, total),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    item(key = "actions") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                        ) {
                            Button(
                                onClick = { playback.playSong(ui.songs.first(), ui.songs) },
                                modifier = Modifier.weight(1f).height(56.dp),
                                shape = ButtonGroupDefaults.connectedLeadingButtonShapes().shape,
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.fav_play))
                            }
                            FilledTonalButton(
                                onClick = { playback.playShuffled(ui.songs) },
                                modifier = Modifier.weight(1f).height(56.dp),
                                shape = ButtonGroupDefaults.connectedTrailingButtonShapes().shape,
                            ) {
                                Icon(Icons.Rounded.Shuffle, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.fav_shuffle))
                            }
                        }
                    }
                    items(ui.songs, key = { it.id }) { song ->
                        SwipeSongRow(
                            song = song,
                            isPlaying = player.currentSong?.id == song.id,
                            selected = selection.isSelected(song.id),
                            selectionActive = selection.active,
                            onClick = { if (!selection.onTap(song.id)) playback.playSong(song, ui.songs) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (selection.active) selection.onLongPress(song.id) else actions.show(song)
                            },
                            onRemove = { removeWithUndo(FavoritesViewModel.songKey(song.id), song.title, song) },
                            onMore = { actions.show(song) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    SongActionsHost(
        state = actions,
        snackbarHostState = snackbar,
        onSelect = { selection.onLongPress(it.id) },
        onGoToAlbum = { onNavigate(albumKeyOf(it)) },
        onGoToArtist = { onNavigate(artistKeyOf(it)) },
    )
    addToPlaylistIds?.let { ids ->
        PlaylistSelectorSheetHost(
            songIds = ids,
            onDismiss = { addToPlaylistIds = null; selection.clear() },
            onMessage = { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } },
        )
    }
    if (showSort) FavSortSheet(
        sort = ui.sort,
        ascending = ui.ascending,
        onChange = { s, asc -> vm.setSort(s, asc) },
        onDismiss = { showSort = false },
    )
}

@Composable
private fun SwipeSongRow(
    song: Song,
    isPlaying: Boolean,
    selected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberSwipeToDismissBoxState()
    val removeLabel = stringResource(R.string.fav_remove)
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        gesturesEnabled = !selectionActive,
        onDismiss = { onRemove() },
        backgroundContent = {
            val start = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
            ) { Icon(Icons.Rounded.HeartBroken, null, tint = MaterialTheme.colorScheme.onErrorContainer) }
        },
    ) {
        SongRow(
            title = song.title,
            artist = song.artist,
            durationMs = song.durationMs,
            onClick = onClick,
            onLongClick = onLongClick,
            artwork = song.artworkPath,
            showArtwork = true,
            isPlaying = isPlaying,
            selected = selected,
            onMoreClick = onMore,
            accessibilityActions = listOf(SongRowAction(removeLabel, onRemove)),
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AlbumCarousel(albums: List<AlbumGroup>, onOpen: (AlbumGroup) -> Unit, onRemove: (AlbumGroup) -> Unit) {
    val state = rememberCarouselState { albums.size }
    val removeLabel = stringResource(R.string.fav_remove)
    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = 168.dp,
        itemSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth().height(200.dp).padding(vertical = 8.dp),
    ) { i ->
        val album = albums[i]
        Box(
            Modifier
                .fillMaxSize()
                .maskClip(MaterialTheme.shapes.small)
                .semantics { customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(album); true }) }
                .combinedClickable(onClick = { onOpen(album) }, onLongClick = { onRemove(album) }),
        ) {
            AlbumArt(
                model = album.songs.firstOrNull { it.artworkPath != null }?.artworkPath,
                title = album.name,
                artist = album.artist,
                size = ArtSize.Medium,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistStrip(artists: List<ArtistGroup>, onOpen: (ArtistGroup) -> Unit, onRemove: (ArtistGroup) -> Unit) {
    val removeLabel = stringResource(R.string.fav_remove)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(artists, key = { it.key() }) { artist ->
            Column(
                Modifier
                    .width(96.dp)
                    .animateItem()
                    .semantics(mergeDescendants = true) {
                        customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(artist); true })
                    }
                    .combinedClickable(onClick = { onOpen(artist) }, onLongClick = { onRemove(artist) }),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ArtistAvatar(artist.localImagePath, artist.name, Modifier.size(88.dp))
                Spacer(Modifier.height(6.dp))
                Text(artist.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FavSortSheet(sort: FavSort, ascending: Boolean, onChange: (FavSort, Boolean) -> Unit, onDismiss: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.fav_sort_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        listOf(
            FavSort.Name to R.string.fav_sort_name,
            FavSort.Duration to R.string.fav_sort_duration,
            FavSort.Date to R.string.fav_sort_date,
        ).forEach { (option, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .selectable(selected = sort == option, role = Role.RadioButton, onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onChange(option, ascending); onDismiss()
                    })
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = sort == option, onClick = null)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            listOf(true to R.string.fav_sort_ascending, false to R.string.fav_sort_descending).forEachIndexed { i, (asc, label) ->
                ToggleButton(
                    checked = ascending == asc,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onChange(sort, asc)
                    },
                    shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(label)) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
