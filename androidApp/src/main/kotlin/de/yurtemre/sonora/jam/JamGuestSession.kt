package de.yurtemre.sonora.jam

import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.jam.ClockSync
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
import de.yurtemre.sonora.shared.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * One guest's time in a Jam: the connection, its failures and the guest's requests. The host's states go to
 * [JamGuestPlayer] (sync rules) once the clock offset is known. A host-driven song change is a queue load, never a
 * local skip, so it is not counted as a skip; the guest's own [request] of Next is.
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
    /** The player was closed mid-Jam: the controller leaves the Jam. */
    private val onLocalClose: () -> Unit = {},
) {
    private val library = JamLibrary(env.library)
    private val player = JamGuestPlayer(scope, playback, env, library) { link.clock }
    private val mirror get() = player.mirror
    private var state: JamState? = null
    private var sync = JamSyncState.Syncing
    private var closeWatch: Job? = null
    private var clockJob: Job? = null
    private var clockJobFor: ClockSync? = null
    private var job: Job? = null
    /** Set when this side ends the connection on purpose (the network changed). */
    private var closing: JamEndReason? = null
    private var left = false

    fun start() {
        publishJoined()
        job = scope.launch { run() }
        closeWatch = watchClose()
    }

    /** Leaves at once: the connection closes, the guest's own queue comes back. */
    fun leave() {
        left = true
        job?.cancel()
        player.cancel()
        clockJob?.cancel()
        closeWatch?.cancel()
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
            player.keepCurrentSongOnly()
            link = reconnect() ?: run {
                finish(closing ?: JamEndReason.HostGone)
                return
            }
            mirror.untrim() // the cut was for the drop: the host's next state brings the following songs back
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
        player.cancel()
        clockJob?.cancel()
        link.close()
        sync = JamSyncState.Ending
        publishJoined()
        if (mirror.active && mirror.view().playing) {
            player.keepCurrentSongOnly()
            playback.player.first { !it.isPlaying && !it.isBuffering }
        }
        mirror.restore()
        closeWatch?.cancel()
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
        if (sync == JamSyncState.Syncing) {
            // Uptime clocks of two phones differ by hours: never plan a start before the first pong is in.
            awaitClock()
            return
        }
        player.onState(s)
    }

    /** Re-plans the latest state as soon as the connection's clock offset is known. */
    private fun awaitClock() {
        val clock = link.clock
        if (clockJob?.isActive == true && clockJobFor === clock) return
        clockJob?.cancel()
        clockJobFor = clock // a reconnect brings a new connection with its own clock
        clockJob = scope.launch {
            while (synchronized(clock) { clock.offsetMs() } == null) delay(CLOCK_POLL_MS)
            state?.let(::onState)
        }
    }

    /** "Close" on the player (notification or app) empties the mirrored queue: that leaves the Jam. */
    private fun watchClose() = scope.launch {
        var loaded = false
        playback.queue.collect { q ->
            when {
                !mirror.active -> loaded = false
                q.items.isNotEmpty() -> loaded = true
                loaded -> onLocalClose()
            }
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
        /** How often a state that arrived before the first clock sample is re-checked. */
        const val CLOCK_POLL_MS = 50L
    }
}
