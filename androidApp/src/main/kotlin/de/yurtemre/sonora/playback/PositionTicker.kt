package de.yurtemre.sonora.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lifecycle-aware playback position stream (PLAY-25). Ticks every [periodMs] only while playing,
 * at least one collector is subscribed and the app is in the foreground. Small backward moves
 * (< 1500 ms) without a seek/track-change discontinuity are dropped.
 */
class PositionTicker(
    private val scope: CoroutineScope,
    private val position: () -> Long,
    private val isPlaying: () -> Boolean,
    private val periodMs: Long = 200L,
) {
    private val _positions = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 16)
    val positions: Flow<Long> = _positions.asSharedFlow()

    private var lastEmitted: Long? = null
    private var foreground = true
    private var active = false
    private var tickJob: Job? = null
    private var hasSubscribers = false

    init {
        scope.launch {
            _positions.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { hasSubs ->
                if (hasSubs && !active) { lastEmitted = null; emit(allowBackward = true) }
                sync(hasSubs)
            }
        }
    }

    val isTicking: Boolean get() = tickJob?.isActive == true

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        sync(hasSubscribers)
    }

    /** Any player state change (play/pause/buffering). */
    fun onPlayerEvent() {
        if (!active) return
        emit()
        tickJob?.cancel()
        tickJob = if (isPlaying()) scope.launch {
            while (isActive) { delay(periodMs); emit() }
        } else null
    }

    /** Seeks and track changes: the only events allowed to move the position backward. */
    fun onDiscontinuity() { if (active) emit(allowBackward = true) }

    private fun sync(hasSubs: Boolean) {
        hasSubscribers = hasSubs
        val shouldRun = foreground && hasSubs
        if (shouldRun && !active) {
            active = true
            onPlayerEvent()
        } else if (!shouldRun && active) {
            active = false
            lastEmitted = null
            tickJob?.cancel(); tickJob = null
        }
    }

    private fun emit(allowBackward: Boolean = false) {
        val p = position()
        val last = lastEmitted
        if (!allowBackward && last != null && p < last && last - p < MAX_DROPPED_CORRECTION_MS) return
        if (p == last) return
        lastEmitted = p
        _positions.tryEmit(p)
    }

    /** Wires this ticker to a Media3 [player]; returns the listener so callers can remove it. */
    fun attach(player: Player): Player.Listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = onPlayerEvent()
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) = onDiscontinuity()
    }.also { player.addListener(it) }

    companion object {
        const val MAX_DROPPED_CORRECTION_MS = 1500L
    }
}
