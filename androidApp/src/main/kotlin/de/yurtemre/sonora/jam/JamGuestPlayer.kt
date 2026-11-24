package de.yurtemre.sonora.jam

import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.jam.ClockSync
import de.yurtemre.sonora.shared.jam.GuestPlan
import de.yurtemre.sonora.shared.jam.JamState
import de.yurtemre.sonora.shared.jam.nextPlayableIndex
import de.yurtemre.sonora.shared.jam.planPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Puts the host's [JamState]s on the guest's player ([JamMirror]).
 *
 * Sound quality beats exact sync: never a speed or pitch change and never a seek while a song plays. A song starts
 * when the local clock reaches the host's anchor (plus the speaker delay), or at once where the host is now when
 * that time has passed. Drift above 2 s seen while playing cuts the player's run to the current song, so the guest
 * can never start the next song before the host (a guest ahead goes quiet at the song's end; one behind finishes its
 * song) and the next song starts from the host's frame at the host's position; the same drift is also corrected by
 * one seek at the next pause or resume. In sync, the player rolls on to the next song by itself, gapless.
 * Songs this phone lacks never reach the player (no stats); the next one it has waits, cued at its start.
 */
internal class JamGuestPlayer(
    private val scope: CoroutineScope,
    private val playback: PlaybackConnection,
    private val env: JamEnvironment,
    private val library: JamLibrary,
    /** The current connection's clock (read inside `synchronized`). */
    private val clock: () -> ClockSync,
) {
    val mirror = JamMirror(playback)
    private var latest: JamState? = null
    /** Drift above the threshold was seen while playing: seek at the next pause or resume. */
    private var pendingSeek = false
    private var startJob: Job? = null
    /** Waits for this phone's song to end before the next one starts (trailing or trimmed). */
    private var tailJob: Job? = null

    fun cancel() {
        startJob?.cancel()
        tailJob?.cancel()
    }

    fun onState(s: JamState) {
        latest = s
        cancel()
        val now = env.nowMs()
        val v = mirror.view()
        val current = s.queue.getOrNull(s.index)?.let(library::match)
        val sameSong = mirror.active && current != null && v.songId == current.id
        val actual = if (sameSong) v.positionMs else null
        val c = clock()
        val plan = synchronized(c) { planPlayback(s, library.songs(), c, now, actual) }
        when {
            plan is GuestPlan.Skip -> onMissing(s, v)
            sameSong -> onSameSong(s, plan as GuestPlan.Play, v, now)
            else -> onNewSong(s, plan as GuestPlan.Play, v, now)
        }
    }

    /** A dropped connection or the end of the Jam: the current song plays on, nothing follows it. */
    fun keepCurrentSongOnly() {
        if (mirror.active) mirror.trimToCurrent(mirror.view())
    }

    private fun onSameSong(s: JamState, plan: GuestPlan.Play, v: LocalView, now: Long) {
        if (s.playing) {
            if (v.playing) {
                if (plan.seekOnNextBoundary) {
                    pendingSeek = true // never mid-song: the seek waits for the next pause
                    mirror.trimToCurrent(v) // and the next song waits for the host
                } else {
                    refreshRun(s, v)
                }
                return
            }
            val start = jamStart(plan, now, env.speakerDelayMs())
            if (plan.seekOnNextBoundary || pendingSeek) playback.seekTo(start.positionMs) // paused: inaudible
            pendingSeek = false
            mirror.untrim()
            refreshRun(s, v)
            startAt(start.atLocalMs ?: now)
        } else {
            // The host stopped at the end of the queue's last song: ours ends by itself a moment later.
            val queueOver = s.index == s.queue.lastIndex && s.positionMs >= s.queue[s.index].durationMs - NATURAL_END_MS
            if (v.playing && queueOver) return
            mirror.pause() // also while still buffering
            if (plan.seekOnNextBoundary || pendingSeek) playback.seekTo(plan.positionMs)
            pendingSeek = false
            mirror.untrim()
            refreshRun(s, v.copy(playing = false))
        }
    }

    private fun onNewSong(s: JamState, plan: GuestPlan.Play, v: LocalView, now: Long) {
        val previous = s.queue.getOrNull(s.index - 1)?.let(library::match)
        val remaining = v.remainingMs ?: Long.MAX_VALUE
        if (s.playing && v.playing && v.songId != null && v.songId == previous?.id && remaining < TAIL_WAIT_MS) {
            // In sync, our player moves on by itself (gapless). Otherwise our song finishes first, uncut.
            if (!mirror.trimmed && nextInRun(v) == plan.song.id && remaining < NATURAL_END_MS) return
            mirror.trimToCurrent(v) // else the player would roll on into the next song from 0 by itself
            awaitSongEnd(v.songId)
            return
        }
        pendingSeek = false
        val run = library.runFrom(s, s.index)
        if (!s.playing) {
            mirror.load(run, 0, plan.positionMs, play = false)
            return
        }
        val start = jamStart(plan, now, env.speakerDelayMs())
        if (start.atLocalMs == null) {
            mirror.load(run, 0, start.positionMs, play = true)
            logStartGap(now)
        } else {
            mirror.load(run, 0, start.positionMs, play = false)
            startAt(start.atLocalMs)
        }
    }

    /** The host plays a song this phone lacks: silence, with the next song it has cued at its start. */
    private fun onMissing(s: JamState, v: LocalView) {
        pendingSeek = false
        val previous = s.queue.getOrNull(s.index - 1)?.let(library::match)
        val finishing = mirror.active && v.playing && v.songId == previous?.id && (v.remainingMs ?: Long.MAX_VALUE) < NATURAL_END_MS
        if (finishing) return // its run ends right there: the player stops by itself
        val next = nextPlayableIndex(s, library.songs(), s.index + 1)
        if (next == null) {
            if (v.playing) mirror.pause()
            return
        }
        val run = library.runFrom(s, next)
        val cued = mirror.active && !v.playing && v.songId == run.first().id && (v.positionMs ?: 0L) == 0L
        if (!cued) mirror.load(run, 0, 0, play = false)
    }

    /** Keeps the playing entry and replaces what follows it when the host's queue changed (not while trimmed). */
    private fun refreshRun(s: JamState, v: LocalView) {
        if (mirror.trimmed) return
        val position = v.positionMs ?: return // the player has not reported the last load yet: the next frame does it
        val run = library.runFrom(s, s.index)
        val loaded = mirror.run.dropWhile { it.id != v.songId }
        if (run.map { it.id } != loaded.map { it.id }) mirror.load(run, 0, position, v.playing)
    }

    private fun awaitSongEnd(songId: Long) {
        tailJob = scope.launch {
            playback.player.first { !it.isPlaying || it.currentSong?.id != songId }
            latest?.let(::onState)
        }
    }

    private fun nextInRun(v: LocalView): Long? {
        val i = mirror.run.indexOfFirst { it.id == v.songId }
        return if (i < 0) null else mirror.run.getOrNull(i + 1)?.id
    }

    /** Plays when the local clock reaches [atLocalMs] (a delayed start, never a faster one). */
    private fun startAt(atLocalMs: Long) {
        startJob = scope.launch {
            val wait = atLocalMs - env.nowMs()
            if (wait > 0) delay(wait)
            mirror.play()
            logStartGap(atLocalMs)
        }
    }

    /** Debug builds only: how late the first audio came after the intended start, with the clock offset. */
    private fun logStartGap(intendedLocalMs: Long) {
        val log = env.debugLog ?: return
        val c = clock()
        scope.launch {
            withTimeoutOrNull(START_LOG_TIMEOUT_MS) { playback.player.first { it.isPlaying } } ?: return@launch
            val gap = env.nowMs() - intendedLocalMs
            val offset = synchronized(c) { c.offsetMs() }
            log("Jam start gap $gap ms (intended $intendedLocalMs, clock offset $offset ms, speaker delay ${env.speakerDelayMs()} ms)")
        }
    }

    companion object {
        /** A song this close to its end rolls on to the next by itself when in sync. */
        const val NATURAL_END_MS = 3_000L
        /** A song this close to its end finishes before the next one starts (instead of being cut). */
        const val TAIL_WAIT_MS = 10_000L
        private const val START_LOG_TIMEOUT_MS = 10_000L
    }
}
