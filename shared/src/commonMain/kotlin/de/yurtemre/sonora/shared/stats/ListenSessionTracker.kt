package de.yurtemre.sonora.shared.stats

import de.yurtemre.sonora.shared.repo.api.Clock
import kotlin.math.roundToLong

/** Stats events emitted by [ListenSessionTracker] (STAT-01, STAT-02). */
sealed interface StatsEvent {
    data class RecordRecent(val songId: Long, val atMs: Long) : StatsEvent
    /** Weekday / local date are derived from [atMs] by the recorder. */
    data class RecordComplete(val songId: Long, val atMs: Long, val playlistId: String?) : StatsEvent
    data class RecordSkip(val songId: Long) : StatsEvent
    data class RecordRestart(val songId: Long) : StatsEvent
    data object RecordShuffleSession : StatsEvent
}

/**
 * Event-driven listen-session tracker (STAT-01/02, audit/data §11.1, audit/playback §7.2).
 * The host feeds player events with current positions, owns the one-shot timer via [schedule]
 * (delay ms, or null = cancel) and calls [onTimerFired] with the then-current position.
 */
class ListenSessionTracker(
    private val clock: Clock,
    private val schedule: (Long?) -> Unit,
    private val emit: (StatsEvent) -> Unit,
) {
    private var songId: Long? = null
    private var durationMs = 0L
    private var playlistId: String? = null
    private var playing = false
    private var speed = 1f
    private var positionMs = 0L
    private var recentDone = false
    private var completeDone = false
    private var pauseDepth = 0

    /** Nesting counter: while > 0 nothing is emitted (restore). */
    fun pauseRecording() { pauseDepth++ }
    fun resumeRecording() { if (pauseDepth > 0) pauseDepth-- }
    val isRecordingPaused: Boolean get() = pauseDepth > 0

    /** New item, auto-advance or repeat-one loop: fresh playthrough. */
    fun onItemStarted(songId: Long?, durationMs: Long, playlistId: String?, positionMs: Long = 0) {
        this.songId = songId; this.durationMs = durationMs; this.playlistId = playlistId
        this.positionMs = positionMs; resetSession(); rearm()
    }

    fun onPlayingChanged(playing: Boolean, positionMs: Long) { this.playing = playing; this.positionMs = positionMs; check() }

    fun onSpeedChanged(speed: Float, positionMs: Long) { this.speed = speed; this.positionMs = positionMs; rearm() }

    /** Seek: < 3 s from > 3 s counts a restart; any landing < 3 s resets the session. */
    fun onSeek(fromMs: Long, toMs: Long) {
        if (toMs < RESTART_MS && fromMs > RESTART_MS) record { StatsEvent.RecordRestart(it) }
        positionMs = toMs
        if (toMs < RESTART_MS) resetSession()
        check()
    }

    /** UI "next": skip unless on the last queue item. */
    fun onSkipNext(isLastItem: Boolean) { if (!isLastItem) record { StatsEvent.RecordSkip(it) } }

    /** UI "previous" with position > 3 s → restart. */
    fun onPrevious(positionMs: Long) { if (positionMs > RESTART_MS) record { StatsEvent.RecordRestart(it) } }

    fun onShuffleSessionStarted() { if (pauseDepth == 0) emit(StatsEvent.RecordShuffleSession) }

    fun onTimerFired(positionMs: Long) { this.positionMs = positionMs; check() }

    private fun resetSession() { recentDone = false; completeDone = false }

    private inline fun record(make: (Long) -> StatsEvent) {
        val id = songId ?: return
        if (pauseDepth == 0) emit(make(id))
    }

    private fun check() {
        val id = songId
        if (playing && id != null && durationMs > 0) {
            val reached = positionMs + TOLERANCE_MS
            val now = clock.nowMs()
            if (!recentDone && reached >= recentMilestone(durationMs)) {
                recentDone = true; record { StatsEvent.RecordRecent(it, now) }
            }
            if (!completeDone && reached >= completeMilestone(durationMs)) {
                completeDone = true; record { StatsEvent.RecordComplete(it, now, playlistId) }
            }
        }
        rearm()
    }

    private fun rearm() {
        if (!playing || songId == null || durationMs <= 0 || speed <= 0f) return schedule(null)
        val next = listOfNotNull(
            recentMilestone(durationMs).takeIf { !recentDone },
            completeMilestone(durationMs).takeIf { !completeDone },
        ).minOrNull() ?: return schedule(null)
        schedule(((next - positionMs).coerceAtLeast(0) / speed).roundToLong())
    }

    companion object {
        const val RESTART_MS = 3_000L
        const val TOLERANCE_MS = 500L
        fun recentMilestone(durationMs: Long): Long =
            (durationMs * 0.25).roundToLong().coerceIn(10_000L, 30_000L).coerceAtMost(durationMs)
        fun completeMilestone(durationMs: Long): Long = (durationMs * 0.8).roundToLong()
    }
}
