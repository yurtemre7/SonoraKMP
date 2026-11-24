package de.yurtemre.sonora.jam

import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.jam.HostAction
import de.yurtemre.sonora.shared.jam.JamAction
import de.yurtemre.sonora.shared.jam.JamEvent
import de.yurtemre.sonora.shared.jam.JamHostState
import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.shared.jam.JamSong
import de.yurtemre.sonora.shared.jam.JamSongMatcher
import de.yurtemre.sonora.shared.jam.JamStateFrame
import de.yurtemre.sonora.shared.jam.apply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The host's side of a Jam. The host's own player is the truth: its queue and transport changes (debounced, so one
 * user action is one frame) become [HostAction]s on [JamHostState] and are broadcast as a [JamStateFrame]. A guest
 * that joins or reconnects gets the current state on its own. Guest requests pass the reducer's permission rules,
 * then act on the host's player (never recorded as the host's own skip); the resulting change is broadcast like
 * any other. Smart shuffle is the host's own
 * setting: guests only mirror the order.
 */
@OptIn(FlowPreview::class)
internal class JamHostSession(
    private val scope: CoroutineScope,
    private val playback: PlaybackConnection,
    private val env: JamEnvironment,
    private val transport: JamHostTransport,
    private var code: String,
    private val port: Int,
    settings: JamSettings,
    private val publish: (JamUiState) -> Unit,
) {
    private var hostState = JamHostState.initial(settings)
    private var codeExhausted = false
    private val jobs = mutableListOf<Job>()
    private var advertisement: AutoCloseable? = null
    private var lastBroadcastAtMs = 0L
    private var lastBroadcastUid: Long? = null

    private data class Snapshot(val queue: List<JamSong>, val index: Int, val entryUid: Long?, val playing: Boolean)

    fun start() {
        publishUi()
        advertisement = runCatching { env.advertise(port, env.deviceName()) }.getOrNull() // manual IP:port still works
        jobs += scope.launch {
            combine(playback.queue, playback.player) { q, p ->
                Snapshot(q.items.map { it.song.toJamSong() }, q.currentIndex, p.currentEntryUid, p.isPlaying)
            }
                .debounce(JamController.HOST_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { broadcastState() }
        }
        jobs += scope.launch { transport.events.collect(::onEvent) }
        jobs += scope.launch { playback.mfx.collect { publishUi() } }
        jobs += scope.launch { watchSeeks() }
        jobs += scope.launch {
            transport.codeExhausted.collect {
                codeExhausted = it
                publishUi()
            }
        }
    }

    fun setSettings(settings: JamSettings) {
        hostState = hostState.apply(JamEvent.HostAction(HostAction.SetSettings(settings)))
        broadcastState()
        publishUi()
    }

    val settings: JamSettings get() = hostState.settings

    fun kick(guestId: String) = transport.kick(guestId)

    /** A fresh code (after too many wrong codes): guests in the Jam stay. */
    fun newCode() {
        var next: String
        do next = env.newCode() while (next == code)
        transport.replaceCode(next)
        code = next
        codeExhausted = false
        publishUi()
    }

    fun end() {
        jobs.forEach { it.cancel() }
        runCatching { advertisement?.close() }
        transport.stop()
    }

    /** [hostState] brought up to the player as it is right now (queue, index, playing, position, host time). */
    private fun synced(): JamHostState {
        val q = playback.queue.value
        val queue = q.items.map { it.song.toJamSong() }
        var s = hostState
        if (queue != s.state.queue || q.currentIndex != s.state.index) {
            s = s.apply(JamEvent.HostAction(HostAction.SetQueue(queue, q.currentIndex)))
        }
        val now = env.nowMs()
        val position = playback.currentPositionMs()
        val action = if (playback.player.value.isPlaying) HostAction.Play(position, now) else HostAction.Pause(position, now)
        return s.apply(JamEvent.HostAction(action))
    }

    /**
     * A seek changes neither the queue nor play/pause, so the snapshot flow misses it: every [SEEK_CHECK_MS] the
     * player's position is compared with the last broadcast's extrapolation and a jump goes out as a new state.
     * With no broadcast for [KEEPALIVE_MS] the state goes out again (a guest treats an unchanged state as a no-op).
     */
    private suspend fun watchSeeks() {
        while (true) {
            delay(SEEK_CHECK_MS)
            if (env.nowMs() - lastBroadcastAtMs >= KEEPALIVE_MS) {
                broadcastState()
                continue
            }
            val s = hostState.state
            val p = playback.player.value
            // Another entry (a song change the debounce has not sent yet) is not a seek.
            if (!s.playing || !p.isPlaying || p.currentEntryUid != lastBroadcastUid) continue
            val expected = s.positionMs + (env.nowMs() - s.hostTimeMs)
            if (abs(playback.currentPositionMs() - expected) > SEEK_TOLERANCE_MS) broadcastState()
        }
    }

    private fun broadcastState() {
        hostState = synced()
        lastBroadcastAtMs = env.nowMs()
        lastBroadcastUid = playback.player.value.currentEntryUid
        transport.broadcast(JamStateFrame(hostState.state))
    }

    private fun onEvent(event: JamEvent) {
        when (event) {
            is JamEvent.GuestJoined -> {
                hostState = synced().apply(event)
                // Only to this guest (a join or a reconnect): the others already have this state.
                transport.sendTo(event.id, JamStateFrame(hostState.state))
                publishUi()
            }
            is JamEvent.GuestLeft -> {
                hostState = hostState.apply(event)
                publishUi()
            }
            is JamEvent.AddRequest -> {
                val now = synced()
                if (now.apply(event) == now) return // guests may not add
                JamSongMatcher.match(event.song, env.library())?.let { playback.addToQueue(listOf(it)) }
            }
            is JamEvent.Control -> {
                val now = synced()
                if (now.apply(event) == now) return // not allowed, or nothing to do
                // Next/Previous jump to the entry instead of skipping: a guest's skip is that guest's, not the host's.
                val q = playback.queue.value
                when (event.action) {
                    JamAction.Play -> playback.play()
                    JamAction.Pause -> playback.pause()
                    JamAction.Next -> q.items.getOrNull(q.currentIndex + 1)?.let { playback.skipToQueueItem(it.entryUid) }
                    JamAction.Previous -> q.items.getOrNull(q.currentIndex - 1)?.let { playback.skipToQueueItem(it.entryUid) }
                }
            }
            is JamEvent.HostAction -> Unit
        }
    }

    private fun publishUi() = publish(
        JamUiState.Hosting(
            code = code,
            port = port,
            guests = hostState.guests.map { (id, info) -> JamGuestUi(id, info.name) },
            settings = hostState.settings,
            codeExhausted = codeExhausted,
            hostSpeedEffectActive = playback.mfx.value.isSpeedChanged,
        ),
    )

    companion object {
        /** How often the host checks for a seek (a position jump without a queue or transport change). */
        const val SEEK_CHECK_MS = 2_000L
        /** The current state is sent again this often, so a guest that missed a frame catches up. */
        const val KEEPALIVE_MS = 30_000L
        /** A position this far from the extrapolated one is a seek (well above player jitter). */
        const val SEEK_TOLERANCE_MS = 1_000L
    }
}
