package de.yurtemre.sonora.ui.screens.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One playlist row; [key] stays unique even when a song appears twice (queue-saved playlists). */
data class PlaylistEntry(val key: String, val song: Song)

sealed interface PlaylistDetailState {
    data object Loading : PlaylistDetailState
    data object NotFound : PlaylistDetailState
    data class Loaded(
        val playlist: Playlist,
        val entries: List<PlaylistEntry>,
        val totalMs: Long,
        val favoriteIds: Set<Long>,
    ) : PlaylistDetailState
}

class PlaylistDetailViewModel(
    private val id: String,
    private val playlists: PlaylistRepository,
    library: LibraryRepository,
    private val favorites: FavoritesRepository,
) : ViewModel() {

    val state: StateFlow<PlaylistDetailState> = combine(
        playlists.playlist(id), library.songs, favorites.favoriteSongs,
    ) { p, songs, favs ->
        if (p == null) return@combine PlaylistDetailState.NotFound
        val byId = songs.associateBy { it.id }
        val seen = HashMap<Long, Int>()
        val entries = p.songIds.mapNotNull { sid ->
            val s = byId[sid] ?: return@mapNotNull null
            val n = seen.merge(sid, 1, Int::plus)!!
            PlaylistEntry("$sid#$n", s)
        }
        PlaylistDetailState.Loaded(p, entries, entries.sumOf { it.song.durationMs }, favs.keys)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistDetailState.Loading)

    /** Fire-and-forget writes survive leaving the screen (e.g. delete then pop). */
    private fun write(block: suspend PlaylistRepository.() -> Unit) {
        viewModelScope.launch(NonCancellable) { playlists.block() }
    }

    fun rename(name: String) = write { rename(id, name) }
    fun setDescription(d: String?) = write { setDescription(id, d) }
    fun setCover(bytes: ByteArray?) = write { setCover(id, bytes) }
    fun delete() = write { delete(id) }
    fun reorder(songIds: List<Long>) = write { reorder(id, songIds) }
    fun addSongs(ids: List<Long>) = write { addSongs(id, ids) }

    fun remove(ids: List<Long>) = write { removeSongs(id, ids) }

    /** Undo for a removal: re-add, then restore the previous order. */
    fun restore(previousOrder: List<Long>, removed: List<Long>) = write {
        addSongs(id, removed)
        reorder(id, previousOrder)
    }

    fun setFavorite(songs: List<Song>, favorite: Boolean) {
        viewModelScope.launch(NonCancellable) {
            favorites.setSongsFavorite(songs.associate { it.id to it.filePath }, favorite)
        }
    }

    suspend fun exportM3u(): String? = withContext(Dispatchers.IO) { runCatching { playlists.exportM3u(id) }.getOrNull() }
}
