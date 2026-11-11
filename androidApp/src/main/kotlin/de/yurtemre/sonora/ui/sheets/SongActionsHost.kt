package de.yurtemre.sonora.ui.sheets

import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import de.yurtemre.sonora.R
import de.yurtemre.sonora.platform.ShareHelper
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * State for [SongActionsHost]. Call [show] from a row's long-press (or "more" button); the host then
 * walks the user through actions → playlist selector / song info.
 */
@Stable
class SongActionsState {
    internal enum class Mode { Actions, Playlist, Info }

    var song: Song? by mutableStateOf(null)
        private set
    internal var mode: Mode by mutableStateOf(Mode.Actions)

    val isVisible: Boolean get() = song != null

    fun show(song: Song) {
        this.song = song
        mode = Mode.Actions
    }

    /** Opens the song info sheet directly. */
    fun showInfo(song: Song) {
        this.song = song
        mode = Mode.Info
    }

    /** Opens the playlist selector directly. */
    fun showAddToPlaylist(song: Song) {
        this.song = song
        mode = Mode.Playlist
    }

    fun dismiss() {
        song = null
    }
}

@Composable
fun rememberSongActionsState(): SongActionsState = remember { SongActionsState() }

/**
 * Renders the song long-press menu for [state]; put it once per screen. Playback, favourites and
 * playlists are wired via Koin; navigation stays with the caller ([onGoToAlbum] / [onGoToArtist], hidden
 * when null). [extraActions] adds screen-specific rows (e.g. remove from playlist). Confirmations go to
 * [snackbarHostState]. [onSelect] adds a "Select" row that starts multi-select with this song.
 */
@Composable
fun SongActionsHost(
    state: SongActionsState,
    snackbarHostState: SnackbarHostState,
    onGoToAlbum: ((Song) -> Unit)? = null,
    onGoToArtist: ((Song) -> Unit)? = null,
    extraActions: (Song) -> List<SongSheetAction> = { emptyList() },
    onSelect: ((Song) -> Unit)? = null,
) {
    val song = state.song ?: return
    val selectLabel = androidx.compose.ui.res.stringResource(R.string.home2_select)
    val playback: PlaybackConnection = koinInject()
    val favorites: FavoritesRepository = koinInject()
    val favMap by favorites.favoriteSongs.collectAsState(initial = null)
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    fun snack(msg: String) = scope.launch {
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(msg)
    }
    // Optimistic favourite state; resynced when the repository emits.
    var favOverride by remember(song.id, favMap) { mutableStateOf<Boolean?>(null) }
    val isFavorite = favOverride ?: favMap?.containsKey(song.id) ?: song.isFavorite

    when (state.mode) {
        SongActionsState.Mode.Actions -> SongActionsSheet(
            song = song,
            isFavorite = isFavorite,
            onDismiss = state::dismiss,
            onPlayNext = {
                playback.playNext(listOf(song))
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                snack(resources.getString(R.string.sheets_actions_snack_play_next, song.title))
            },
            onAddToQueue = {
                playback.addToQueue(listOf(song))
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                snack(resources.getString(R.string.sheets_actions_snack_queued, song.title))
            },
            onAddToPlaylist = { state.mode = SongActionsState.Mode.Playlist },
            onToggleFavorite = {
                val next = !isFavorite
                favOverride = next
                haptics.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                scope.launch(NonCancellable) { favorites.setSongsFavorite(mapOf(song.id to song.filePath), next) }
                snack(resources.getString(if (next) R.string.sheets_actions_snack_favorited else R.string.sheets_actions_snack_unfavorited))
            },
            onInfo = { state.mode = SongActionsState.Mode.Info },
            onShare = {
                if (!ShareHelper.share(context, listOf(ShareHelper.Item(song.filePath, song.id.takeIf { it > 0 })))) {
                    snack(resources.getString(R.string.sheets_info_share_failed))
                }
            },
            onGoToAlbum = onGoToAlbum?.let { { it(song) } },
            onGoToArtist = onGoToArtist?.let { { it(song) } },
            extraActions = onSelect?.let { sel ->
                listOf(SongSheetAction(selectLabel, androidx.compose.material.icons.Icons.Rounded.CheckCircle) { sel(song) })
            }.orEmpty() + extraActions(song),
        )
        SongActionsState.Mode.Playlist -> PlaylistSelectorSheetHost(
            songIds = listOf(song.id),
            onDismiss = state::dismiss,
            onMessage = { snack(it) },
        )
        SongActionsState.Mode.Info -> SongInfoSheet(song, onDismiss = state::dismiss)
    }
}
