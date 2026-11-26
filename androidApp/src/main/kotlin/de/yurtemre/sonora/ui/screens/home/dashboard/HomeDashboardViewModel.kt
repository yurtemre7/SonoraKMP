package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.yurtemre.sonora.shared.library.Recommendations
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.stats.HomeGlance
import de.yurtemre.sonora.shared.stats.StatsAggregates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** Home dashboard rows. Lazy (only while the dashboard is on screen), unlike the eager library `HomeViewModel`. */
class HomeDashboardViewModel(library: LibraryRepository, stats: StatsRepository, clock: Clock) : ViewModel() {
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

    private companion object { const val RECENT_LIMIT = 20 }
}
