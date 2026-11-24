package de.yurtemre.sonora.ui.screens.weekly

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.yurtemre.sonora.shared.library.WeeklyMix
import de.yurtemre.sonora.shared.library.WeeklyMixRepository
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.ui.format.CountdownParts
import de.yurtemre.sonora.ui.format.formatCountdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

data class WeeklyMixUi(
    val loaded: Boolean = false,
    val weekStartMs: Long = 0,
    val songs: List<Song> = emptyList(),
    val countdown: CountdownParts = CountdownParts(0, 0, 0),
    val nextRefreshMs: Long = 0,
)

/**
 * Drives the weekly mix. While observed it calls `refreshIfNeeded` at start, once a minute, exactly when
 * the countdown reaches zero, and whenever the library becomes non-empty (the repository never refreshes by itself).
 */
class WeeklyMixViewModel(
    private val repo: WeeklyMixRepository,
    libraryNonEmpty: Flow<Boolean>,
    private val clock: Clock,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val tickMs: Long = 60_000,
) : ViewModel() {

    private val inFlight = MutableStateFlow(0)
    private val firstDone = MutableStateFlow(false)

    /**
     * [WeeklyMixUi.loaded] is false until the first refresh has finished, and while a refresh runs with
     * nothing to show yet; during a rollover refresh the previous songs stay visible.
     */
    val ui: StateFlow<WeeklyMixUi> = flow {
        coroutineScope {
            val now = MutableStateFlow(clock.nowMs())
            launch {
                refresh()
                firstDone.value = true
                while (true) {
                    val t = clock.nowMs()
                    // Wake at the boundary itself rather than up to a tick late.
                    delay(minOf(tickMs, (WeeklyMix.nextRefresh(t, zone()) - t).coerceAtLeast(1)))
                    refresh { now.value = clock.nowMs() }
                }
            }
            launch { libraryNonEmpty.distinctUntilChanged().filter { it }.collect { refresh() } }
            var last = emptyList<Song>()
            emitAll(
                combine(repo.state(zone()), now, inFlight, firstDone) { s, t, running, first ->
                    val busy = running > 0 || !first
                    if (s.songs.isNotEmpty()) last = s.songs
                    val shown = if (s.songs.isEmpty() && busy) last else s.songs
                    WeeklyMixUi(!busy || shown.isNotEmpty(), s.weekStartMs, shown, formatCountdown(s.nextRefreshMs - t), s.nextRefreshMs)
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeeklyMixUi())

    /** Call on resume. */
    fun onResume() { viewModelScope.launch { refresh() } }

    /** Saves the shown mix as a playlist; null on failure. Only call while [WeeklyMixUi.songs] is non-empty. */
    suspend fun save(name: String): Playlist? = try {
        repo.saveAsPlaylist(name)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** [onStart] runs after the in-flight mark is set, so observers never see a stale empty mix unmarked. */
    private suspend fun refresh(onStart: () -> Unit = {}) {
        inFlight.update { it + 1 }
        try {
            onStart()
            repo.refreshIfNeeded(zone())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            inFlight.update { it - 1 }
        }
    }
}
