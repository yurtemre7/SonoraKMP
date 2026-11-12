package de.yurtemre.sonora.ui.screens.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.yurtemre.sonora.shared.library.AlbumGrouper
import de.yurtemre.sonora.shared.library.ArtistGrouper
import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.FavoritesSort
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FavSort { Name, Duration, Date }

data class FavoritesUi(
    val loaded: Boolean = false,
    val songs: List<Song> = emptyList(),
    val albums: List<AlbumGroup> = emptyList(),
    val artists: List<ArtistGroup> = emptyList(),
    val sort: FavSort = FavSort.Date,
    val ascending: Boolean = false,
) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

/** UI-26: favourite songs/albums/artists. Removals are optimistic: hidden at once, committed after the undo window. */
class FavoritesViewModel(
    library: LibraryRepository,
    private val favorites: FavoritesRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    /** Persisted favourites sort (`favorites_sort_by` / `favorites_sort_ascending`, default date descending). */
    private val sortState = settingsRepo.settings
        .map { FavSort.valueOf(it.favoritesSortBy.name) to it.favoritesSortAscending }
        .distinctUntilChanged()
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    val ui: StateFlow<FavoritesUi> = combine(
        library.songs, library.artistImages, favorites.favoriteSongs, favorites.favoriteAlbums, favorites.favoriteArtists,
    ) { songs, images, favSongs, favAlbums, favArtists -> Raw(songs, images, favSongs, favAlbums, favArtists) }
        .combine(sortState) { raw, sort -> raw to sort }
        .combine(hidden) { (raw, sort), hide -> build(raw, sort.first, sort.second, hide) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoritesUi())

    private class Raw(
        val songs: List<Song>, val images: Map<String, String>,
        val favSongs: Map<Long, Long>, val favAlbums: Map<String, Long>, val favArtists: Map<String, Long>,
    )

    private fun build(r: Raw, sort: FavSort, asc: Boolean, hide: Set<String>): FavoritesUi {
        val favSongs = r.songs.filter { it.id in r.favSongs && songKey(it.id) !in hide }
        val cmp: Comparator<Song> = when (sort) {
            FavSort.Name -> compareBy<Song>({ it.titleLower }, { it.id })
            FavSort.Duration -> compareBy<Song>({ it.durationMs }, { it.titleLower })
            FavSort.Date -> compareBy<Song>({ r.favSongs[it.id] ?: 0L }, { it.titleLower })
        }
        val albums = AlbumGrouper.build(r.songs)
        val artists = ArtistGrouper.build(r.songs, albums, r.images)
        return FavoritesUi(
            loaded = true,
            songs = favSongs.sortedWith(if (asc) cmp else cmp.reversed()),
            albums = albums.filter { it.key() in r.favAlbums && albumKey(it.key()) !in hide }
                .sortedByDescending { r.favAlbums[it.key()] },
            artists = artists.filter { it.key() in r.favArtists && artistKey(it.key()) !in hide }
                .sortedByDescending { r.favArtists[it.key()] },
            sort = sort,
            ascending = asc,
        )
    }

    fun setSort(sort: FavSort, ascending: Boolean) {
        viewModelScope.launch { settingsRepo.setFavoritesSort(FavoritesSort.valueOf(sort.name), ascending) }
    }

    fun hide(key: String) { hidden.value += key }
    fun undo(key: String) { hidden.value -= key }

    /** Commits a hidden removal; runs even if the screen is leaving. */
    suspend fun commit(key: String, song: Song? = null) = withContext(NonCancellable) {
        when {
            key.startsWith(SONG) && song != null -> favorites.setSongsFavorite(mapOf(song.id to song.filePath), false)
            key.startsWith(ALBUM) -> favorites.toggleAlbum(key.removePrefix(ALBUM))
            key.startsWith(ARTIST) -> favorites.toggleArtist(key.removePrefix(ARTIST))
        }
        hidden.value -= key
    }

    fun favoriteBulk(songs: List<Song>, favorite: Boolean) {
        viewModelScope.launch { favorites.setSongsFavorite(songs.associate { it.id to it.filePath }, favorite) }
    }

    companion object {
        private const val SONG = "s:"
        private const val ALBUM = "al:"
        private const val ARTIST = "ar:"
        fun songKey(id: Long) = "$SONG$id"
        fun albumKey(key: String) = "$ALBUM$key"
        fun artistKey(key: String) = "$ARTIST$key"
    }
}
