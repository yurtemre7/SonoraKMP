package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.playback.api.PlayerUiState
import de.yurtemre.sonora.shared.model.NowPlayingStyle
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Now Playing state & commands (UI-16/17/22). */
class NowPlayingViewModel(
    private val playback: PlaybackConnection,
    private val favorites: FavoritesRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val player: StateFlow<PlayerUiState> = playback.player
    val positionMs: Flow<Long> = playback.positionMs

    /** Optimistic favourite (song id → value) shown until the repository write lands (P5-26). */
    private val favoriteOverride = MutableStateFlow<Pair<Long, Boolean>?>(null)

    val isFavorite: StateFlow<Boolean> = combine(playback.player, favorites.favoriteSongs, favoriteOverride) { p, f, o ->
        val id = p.currentSong?.id ?: return@combine false
        if (o != null && o.first == id) o.second else id in f
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    val immersive: StateFlow<Boolean> = settings.settings.map { it.immersiveMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    val style: StateFlow<NowPlayingStyle> = settings.settings.map { it.nowPlayingStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), NowPlayingStyle.Modern)

    val blurredBackgrounds: StateFlow<Boolean> = settings.settings.map { it.blurredBackgrounds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    val showVisualizer: StateFlow<Boolean> = settings.settings.map { it.showVisualizer }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    fun togglePlayPause() = playback.togglePlayPause()
    fun next() = playback.skipToNext()
    fun previous() = playback.skipToPrevious()
    fun toggleShuffle() = playback.toggleShuffle()
    fun cycleRepeat() = playback.cycleRepeat()
    fun seekTo(ms: Long) = playback.seekTo(ms)
    fun skipTo(entryUid: Long) = playback.skipToQueueItem(entryUid)

    fun toggleFavorite() {
        val song = player.value.currentSong ?: return
        val wanted = !isFavorite.value
        favoriteOverride.value = song.id to wanted
        viewModelScope.launch {
            try {
                favorites.toggleSong(song.id, song.filePath)
                withTimeoutOrNull(RECONCILE_MS) { favorites.favoriteSongs.first { (song.id in it) == wanted } }
            } finally {
                if (favoriteOverride.value?.first == song.id) favoriteOverride.value = null
            }
        }
    }

    fun toggleImmersive() {
        val next = !immersive.value
        viewModelScope.launch { settings.setImmersiveMode(next) }
    }

    private companion object { const val STOP_MS = 5_000L; const val RECONCILE_MS = 2_000L }
}
