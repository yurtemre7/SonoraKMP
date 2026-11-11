package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.playback.api.PlayerUiState
import de.yurtemre.sonora.shared.model.NowPlayingStyle
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Now Playing state & commands (UI-16/17/22). */
class NowPlayingViewModel(
    private val playback: PlaybackConnection,
    private val favorites: FavoritesRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val player: StateFlow<PlayerUiState> = playback.player
    val positionMs: Flow<Long> = playback.positionMs

    val isFavorite: StateFlow<Boolean> = combine(playback.player, favorites.favoriteSongs) { p, f ->
        p.currentSong?.id?.let { it in f } ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    val immersive: StateFlow<Boolean> = settings.settings.map { it.immersiveMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    val style: StateFlow<NowPlayingStyle> = settings.settings.map { it.nowPlayingStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), NowPlayingStyle.Modern)

    fun togglePlayPause() = playback.togglePlayPause()
    fun next() = playback.skipToNext()
    fun previous() = playback.skipToPrevious()
    fun toggleShuffle() = playback.toggleShuffle()
    fun cycleRepeat() = playback.cycleRepeat()
    fun seekTo(ms: Long) = playback.seekTo(ms)

    fun toggleFavorite() {
        val song = player.value.currentSong ?: return
        viewModelScope.launch { favorites.toggleSong(song.id, song.filePath) }
    }

    fun toggleImmersive() {
        val next = !immersive.value
        viewModelScope.launch { settings.setImmersiveMode(next) }
    }

    private companion object { const val STOP_MS = 5_000L }
}
