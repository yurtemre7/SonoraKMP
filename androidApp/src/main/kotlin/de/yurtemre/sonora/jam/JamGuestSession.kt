package de.yurtemre.sonora.jam

import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.playback.api.PlayerUiState
import de.yurtemre.sonora.shared.jam.GuestPlan
import de.yurtemre.sonora.shared.jam.JamAction
import de.yurtemre.sonora.shared.jam.JamAddRequest
import de.yurtemre.sonora.shared.jam.JamControl
import de.yurtemre.sonora.shared.jam.JamEnd
import de.yurtemre.sonora.shared.jam.JamError
import de.yurtemre.sonora.shared.jam.JamException
import de.yurtemre.sonora.shared.jam.JamFrame
import de.yurtemre.sonora.shared.jam.JamServer
import de.yurtemre.sonora.shared.jam.JamState
import de.yurtemre.sonora.shared.jam.JamStateFrame
import de.yurtemre.sonora.shared.jam.nextPlayableIndex
import de.yurtemre.sonora.shared.jam.planPlayback
import de.yurtemre.sonora.shared.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One guest's time in a Jam: mirrors the host's [JamState]s onto the local player.
 *
 * Sound quality beats exact sync: the guest never changes speed or pitch and never seeks while a song plays. A song
 * starts when the local clock reaches the host's anchor (plus the speaker delay), or at once where the host is now
 * when that time has passed. Drift above 2 s is corrected by one seek, at the next pause or resume (a song change
 * starts at the host's position anyway). Songs this phone lacks never reach the player (no stats); the next one it
 * has waits there, paused. A host-driven song change is a queue load, never a local skip, so it is not counted as a
 * skip; the guest's own [request] of Next is.
 *
 * A dropped connection is retried for [JamServer.RECONNECT_WINDOW_MS] while the current song plays on (nothing
 * after it); if the host is gone, ended the Jam, kicked this guest or the network changed, the current song plays to
 * its end, then the guest's own queue comes back and the state is [JamUiState.Ended].
 */
internal class JamGuestSession(
    private val scope: CoroutineScope,
    private val playback: PlaybackConnection,
    private val env: JamEnvironment,
    private val target: JamRejoin,
    private val name: String,
    private var link: JamGuestLink,
    private val publish: (JamUiState) -> Unit,
) {
    private val mirror = JamMirror(playback)
    private val library = JamLibrary(env.library)
    private var state: JamState? = null
    private var sync = JamSyncState.Syncing
    /** Drift above the threshold was seen while playing: seek at the next pause or resume. */
    private var pendingSeek = false
    private var startJob: Job? = null
    private var job: Job? = null
    /** Set when this side ends the connection on purpose (the network changed). */
    private var closing: JamEndReason? = null
    private var left = false

    fun start() {
        publishJoined()
        job = scope.launch { run() }
    }

    /** Leaves at once: the connection closes, the guest's own queue comes back. */
    fun leave() {
        left = true
        job?.cancel()
        startJob?.cancel()
        link.close()
        mirror.restore()
    }

    /** Wi-Fi went away or changed: end the connection cleanly (finish the song), offer a rejoin. */
    fun onNetworkLost() {
        if (left || closing != null) return
        closing = JamEndReason.NetworkChanged
        link.close()
    }

    /** Guest transport request; only sent when the host lets guests control. Next counts as this guest's skip. */
    fun request(action: JamAction) {
        val s = state ?: return
        if (!s.guestsCanControl || left || closing != null || sync == JamSyncState.Ending) return
        if (action == JamAction.Next && s.index < s.queue.lastIndex) {
            val current = playback.player.value.currentSong
            val jamCurrent = s.queue.getOrNull(s.index)?.let(library::match)
            if (current != null && current.id == jamCurrent?.id) scope.launch { env.recordSkip(current.id) }
        }
        link.send(JamControl(action))
    }

    fun requestAdd(song: Song) {
        val s = state ?: return
        if (s.guestsCanAdd && !left && closing == null) link.send(JamAddRequest(song.toJamSong()))
    }

    // ---- connection ------------------------------------------------------------------------------------------------

    private suspend fun run() {
        while (true) {
            var ended: JamEndReason? = null
            val dropped = try {
                link.frames
                    .takeWhile { frame -> (frame !is JamEnd).also { if (!it) ended = endReason(frame as JamEnd) } }
                    .collect { onFrame(it) }
                false
            } catch (e: JamException) {
                true
            }
            if (!dropped || closing != null) {
                finish(closing ?: ended ?: JamEndReason.HostGone)
                return
            }
            sync = JamSyncState.Reconnecting
            publishJoined()
            keepCurrentSongOnly()
            link = reconnect() ?: run {
                finish(closing ?: JamEndReason.HostGone)
                return
            }
            sync = clockState()
            publishJoined()
        }
    }

    private suspend fun reconnect(): JamGuestLink? {
        val droppedAt = env.nowMs()
        while (closing == null && env.nowMs() - droppedAt < JamServer.RECONNECT_WINDOW_MS) {
            try {
                return env.connector.reconnect(target.host, target.port, link, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: JamException) {
                if (e.error == JamError.ReconnectRefused) return null
            } catch (e: Exception) {
                // Not reachable yet (Wi-Fi coming back): try again.
            }
            delay(RECONNECT_RETRY_MS)
        }
        return null
    }

    /** The current song plays to its end (nothing after it), then the guest's own queue comes back. */
    private suspend fun finish(reason: JamEndReason) {
        startJob?.cancel()
        link.close()
        sync = JamSyncState.Ending
        publishJoined()
        if (mirror.active && playback.player.value.isPlaying) {
            keepCurrentSongOnly()
            playback.player.first { !it.isPlaying && !it.isBuffering }
        }
        mirror.restore()
        publish(JamUiState.Ended(reason, rejoin = target.takeIf { reason == JamEndReason.NetworkChanged }))
    }

    private fun endReason(end: JamEnd) = when (end.reason) {
        JamServer.REASON_KICKED -> JamEndReason.Kicked
        JamServer.REASON_HOST_ENDED -> JamEndReason.HostEnded
        else -> JamEndReason.HostGone
    }

    private fun clockState() =
        if (synchronized(link.clock) { link.clock.offsetMs() } == null) JamSyncState.Syncing else JamSyncState.InSync

    // ---- mirroring -------------------------------------------------------------------------------------------------

    private fun onFrame(frame: JamFrame) {
        if (frame is JamStateFrame) onState(frame.state)
    }

    private fun onState(s: JamState) {
        state = s
        sync = clockState()
        publishJoined()
        startJob?.cancel()
        val now = env.nowMs()
        val p = playback.player.value
        val current = s.queue.getOrNull(s.index)?.let(library::match)
        val sameSong = mirror.active && current != null && p.currentSong?.id == current.id
        val actual = if (sameSong) playback.currentPositionMs() else null
        val plan = synchronized(link.clock) { planPlayback(s, library.songs(), link.clock, now, actual) }
        when {
            plan is GuestPlan.Skip -> onMissing(s, p)
            sameSong -> onSameSong(s, plan as GuestPlan.Play, p, now)
            else -> onNewSong(s, plan as GuestPlan.Play, p, now)
        }
    }

    private fun onSameSong(s: JamState, plan: GuestPlan.Play, p: PlayerUiState, now: Long) {
        refreshRun(s, p)
        if (s.playing) {
            if (p.isPlaying) {
                if (plan.seekOnNextBoundary) pendingSeek = true // never mid-song: wait for the next pause
                return
            }
            val start = jamStart(plan, now, env.speakerDelayMs())
            if (plan.seekOnNextBoundary || pendingSeek) playback.seekTo(start.positionMs) // paused: inaudible
            pendingSeek = false
            startAt(start.atLocalMs ?: now)
        } else {
            if (p.isPlaying) {
                // The host stopped at this song's end (queue over): ours ends by itself a moment later.
                if (s.positionMs >= (s.queue[s.index].durationMs - NATURAL_END_MS)) return
                playback.pause()
            }
            if (plan.seekOnNextBoundary || pendingSeek) playback.seekTo(plan.positionMs)
            pendingSeek = false
        }
    }

    private fun onNewSong(s: JamState, plan: GuestPlan.Play, p: PlayerUiState, now: Long) {
        // The host moved on at the end of the song this phone is finishing: our player moves on by itself, gapless.
        if (s.playing && p.isPlaying && nextInRun(p) == plan.song.id && remainingMs(p) < NATURAL_END_MS) return
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
    private fun onMissing(s: JamState, p: PlayerUiState) {
        pendingSeek = false
        val previous = s.queue.getOrNull(s.index - 1)?.let(library::match)
        val finishing = p.isPlaying && mirror.active && p.currentSong?.id == previous?.id && remainingMs(p) < NATURAL_END_MS
        if (finishing) return // its run ends right there: the player stops by itself
        val next = nextPlayableIndex(s, library.songs(), s.index + 1)
        if (next == null) {
            if (p.isPlaying) playback.pause()
            return
        }
        val run = library.runFrom(s, next)
        val cued = mirror.active && !p.isPlaying && p.currentSong?.id == run.first().id && playback.currentPositionMs() == 0L
        if (!cued) mirror.load(run, 0, 0, play = false)
    }

    /** Keeps the playing entry and replaces what follows it when the host's queue changed. */
    private fun refreshRun(s: JamState, p: PlayerUiState) {
        val run = library.runFrom(s, s.index)
        val loaded = mirror.run.dropWhile { it.id != p.currentSong?.id }
        if (run.map { it.id } != loaded.map { it.id }) {
            mirror.load(run, 0, playback.currentPositionMs(), p.isPlaying)
        }
    }

    private fun keepCurrentSongOnly() {
        val p = playback.player.value
        val current = p.currentSong ?: return
        if (!mirror.active || mirror.run.map { it.id } == listOf(current.id)) return
        mirror.load(listOf(current), 0, playback.currentPositionMs(), p.isPlaying)
    }

    private fun nextInRun(p: PlayerUiState): Long? {
        val i = mirror.run.indexOfFirst { it.id == p.currentSong?.id }
        return if (i < 0) null else mirror.run.getOrNull(i + 1)?.id
    }

    private fun remainingMs(p: PlayerUiState) = p.durationMs - playback.currentPositionMs()

    /** Plays when the local clock reaches [atLocalMs] (a delayed start, never a faster one). */
    private fun startAt(atLocalMs: Long) {
        startJob = scope.launch {
            val wait = atLocalMs - env.nowMs()
            if (wait > 0) delay(wait)
            playback.play()
            logStartGap(atLocalMs)
        }
    }

    /** Debug builds only: how late the first audio came after the intended start, with the clock offset. */
    private fun logStartGap(intendedLocalMs: Long) {
        val log = env.debugLog ?: return
        val clock = link.clock
        scope.launch {
            withTimeoutOrNull(START_LOG_TIMEOUT_MS) { playback.player.first { it.isPlaying } } ?: return@launch
            val gap = env.nowMs() - intendedLocalMs
            val offset = synchronized(clock) { clock.offsetMs() }
            log("Jam start gap ${gap} ms (intended $intendedLocalMs, clock offset $offset ms, speaker delay ${env.speakerDelayMs()} ms)")
        }
    }

    private fun publishJoined() {
        if (left) return
        val s = state
        publish(
            JamUiState.Joined(
                hostName = target.hostName,
                queue = s?.queue?.map { JamQueueItem(it, library.match(it) != null) }.orEmpty(),
                index = s?.index ?: 0,
                playing = s?.playing ?: false,
                canAdd = s?.guestsCanAdd ?: false,
                canControl = s?.guestsCanControl ?: false,
                sync = sync,
            ),
        )
    }

    companion object {
        /** Retry pause while reconnecting after a drop. */
        const val RECONNECT_RETRY_MS = 2_000L
        /** A song this close to its end finishes by itself instead of being cut by a load. */
        const val NATURAL_END_MS = 3_000L
        private const val START_LOG_TIMEOUT_MS = 10_000L
    }
}
