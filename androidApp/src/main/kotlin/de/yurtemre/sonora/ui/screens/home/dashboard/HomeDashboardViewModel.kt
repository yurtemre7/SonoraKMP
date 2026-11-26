package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.yurtemre.sonora.shared.library.AlbumGrouper
import de.yurtemre.sonora.shared.library.JumpBackIn
import de.yurtemre.sonora.shared.library.JumpBackItem
import de.yurtemre.sonora.shared.library.Recommendations
import de.yurtemre.sonora.shared.library.TimeOfDayMix
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.stats.HomeGlance
import de.yurtemre.sonora.shared.stats.StatsAggregates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

/** Home dashboard rows. Lazy (only while the dashboard is on screen), unlike the eager library `HomeViewModel`. */
/** Time-of-day mix for the Home card: the slot it was built for and its songs. */
data class TimeMixUi(val slot: TimeOfDayMix.Slot, val songs: List<Song>)

/** A playable shuffle target: the songs plus the playlist id when it came from a playlist. */
data class ShuffleTarget(val songs: List<Song>, val playlistId: String? = null)

class HomeDashboardViewModel(
    private val library: LibraryRepository,
    stats: StatsRepository,
    clock: Clock,
    private val playlists: PlaylistRepository,
) : ViewModel() {
    val recommended: StateFlow<List<Song>> = combine(library.songs, stats.snapshot) { songs, snap ->
        Recommendations.recommended(songs, snap, clock.nowMs())
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Most recently played first (the same source as the Recently played screen). */
    val recent: StateFlow<List<Song>> = combine(library.songs, stats.snapshot) { songs, snap ->
        StatsAggregates.recentSongs(snap, songs, RECENT_LIMIT)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Stats at a glance + favourite song for the Home cards; null until the first emission. */
    val glance: StateFlow<HomeGlance?> = combine(library.songs, stats.snapshot) { songs, snap ->
        HomeGlance.from(snap, songs)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Slot is taken from the local hour when the snapshot/library changes, which is fresh enough for a Home card. */
    val timeMix: StateFlow<TimeMixUi?> = combine(library.songs, stats.snapshot) { songs, snap ->
        val now = clock.nowMs()
        val zone = TimeZone.currentSystemDefault()
        val hour = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).hour
        val slot = TimeOfDayMix.slotFor(hour)
        TimeMixUi(slot, TimeOfDayMix.songs(songs, snap, slot, zone, now))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Up to three recently played albums for "Jump back in". */
    val jumpBack: StateFlow<List<JumpBackItem>> = combine(library.songs, stats.snapshot) { songs, snap ->
        JumpBackIn.recentAlbums(songs, snap, 3)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True when at least one playlist resolves to a library song (enables the Playlists quick action). */
    val hasPlayablePlaylist: StateFlow<Boolean> = combine(library.songs, playlists.playlists) { songs, pls ->
        val ids = songs.mapTo(HashSet()) { it.id }
        pls.any { p -> p.songIds.any { it in ids } }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Random target for a tile's quick action; null when there is nothing to play. */
    suspend fun randomTarget(tab: de.yurtemre.sonora.ui.navigation.LibraryTab): ShuffleTarget? = withContext(Dispatchers.Default) {
        val songs = library.songs.first()
        when (tab) {
            de.yurtemre.sonora.ui.navigation.LibraryTab.Songs -> songs.takeIf { it.isNotEmpty() }?.let { ShuffleTarget(it) }
            de.yurtemre.sonora.ui.navigation.LibraryTab.Albums ->
                AlbumGrouper.build(songs).randomOrNull()?.songs?.takeIf { it.isNotEmpty() }?.let { ShuffleTarget(it) }
            de.yurtemre.sonora.ui.navigation.LibraryTab.Artists ->
                songs.groupBy { it.artist }.values.toList().randomOrNull()?.let { ShuffleTarget(it) }
            de.yurtemre.sonora.ui.navigation.LibraryTab.Playlists -> {
                val byId = songs.associateBy { it.id }
                playlists.playlists.first().mapNotNull { p ->
                    val resolved = p.songIds.mapNotNull { byId[it] }
                    if (resolved.isEmpty()) null else ShuffleTarget(resolved, p.id)
                }.randomOrNull()
            }
        }
    }

    private companion object { const val RECENT_LIMIT = 20 }
}
