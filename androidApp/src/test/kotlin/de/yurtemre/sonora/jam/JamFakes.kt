package de.yurtemre.sonora.jam

import de.yurtemre.sonora.playback.api.MfxUiState
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.playback.api.PlayerUiState
import de.yurtemre.sonora.playback.api.QueueItemUi
import de.yurtemre.sonora.playback.api.QueueSnapshot
import de.yurtemre.sonora.playback.api.QueueUiState
import de.yurtemre.sonora.playback.api.SleepTimerUiState
import de.yurtemre.sonora.shared.jam.ClockSync
import de.yurtemre.sonora.shared.jam.JamError
import de.yurtemre.sonora.shared.jam.JamEvent
import de.yurtemre.sonora.shared.jam.JamException
import de.yurtemre.sonora.shared.jam.JamFrame
import de.yurtemre.sonora.shared.jam.JamSong
import de.yurtemre.sonora.shared.model.EqPreset
import de.yurtemre.sonora.shared.model.MfxPreset
import de.yurtemre.sonora.shared.model.QueueState
import de.yurtemre.sonora.shared.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow

fun Song.jam() = JamSong(title, artist, album, durationMs)

/** One `setMirrorQueue` call. */
data class MirrorLoad(val songIds: List<Long>, val index: Int, val positionMs: Long, val play: Boolean, val repeatOne: Boolean = false)

/**
 * A [PlaybackConnection] that records every command. The player follows the mirror commands so the controller
 * sees what a real player would report; [effectCalls] collects every speed, pitch and effect call (must stay empty).
 */
class RecordingPlayback(private val nowMs: () -> Long) : PlaybackConnection {
    override val player = MutableStateFlow(PlayerUiState(isConnected = true))
    override val queue = MutableStateFlow(QueueUiState())
    override val sleepTimer = MutableStateFlow<SleepTimerUiState>(SleepTimerUiState.Inactive)
    override val mfx = MutableStateFlow(MfxUiState())
    override val positionMs: Flow<Long> = flowOf(0L)

    val calls = mutableListOf<String>()
    val effectCalls = mutableListOf<String>()
    val mirrorLoads = mutableListOf<MirrorLoad>()
    val seeks = mutableListOf<Long>()
    /** Every setMirrorRate call: (speed, pitch). */
    val mirrorRates = mutableListOf<Pair<Float, Float>>()
    val restored = mutableListOf<QueueSnapshot?>()
    /** Local time of every play() call. */
    val playAt = mutableListOf<Long>()
    var snapshot: QueueSnapshot? = QueueSnapshot(QueueState(currentIndex = 0), 12_345L)
    var snapshotCalls = 0
    private var mirrorSongs: List<Song> = emptyList()

    /** Position as the player would report it: advances with the clock while playing. */
    private var basePosition = 0L
    private var baseAt = 0L
    var position: Long
        get() = if (player.value.isPlaying) basePosition + ((nowMs() - baseAt) * mfx.value.effectiveSpeed).toLong() else basePosition
        set(value) { basePosition = value; baseAt = nowMs() }

    private fun setPlaying(playing: Boolean) {
        val pos = position
        player.value = player.value.copy(isPlaying = playing)
        position = pos
    }

    /** Host side: the user's queue. */
    fun hostQueue(songs: List<Song>, index: Int, playing: Boolean, positionMs: Long = 0, uidBase: Long = 0) {
        queue.value = QueueUiState(songs.mapIndexed { i, s -> QueueItemUi(uidBase + i, s) }, index)
        player.value = player.value.copy(
            currentSong = songs.getOrNull(index), currentEntryUid = uidBase + index, isPlaying = playing,
            durationMs = songs.getOrNull(index)?.durationMs ?: 0,
        )
        position = positionMs
    }

    /** The service paused for a system reason (headphones unplugged, audio focus lost) and says so. */
    fun systemPause() {
        val pos = position
        player.value = player.value.copy(isPlaying = false, systemPauseId = player.value.systemPauseId + 1)
        position = pos
    }

    /** The player started by itself (e.g. resume when headphones come back). */
    fun resumeBySystem() { setPlaying(true) }

    /** The current song ended by itself (end of the mirror queue). */
    fun finishSong() {
        position = player.value.durationMs
        player.value = player.value.copy(isPlaying = false, isCompleted = true)
    }

    override fun currentPositionMs(): Long = position
    override fun queueSnapshot(): QueueSnapshot? { snapshotCalls++; calls += "queueSnapshot"; return snapshot }
    /**
     * Like the playback service: the playing entry is kept only when the player already plays it (then a requested
     * play is honoured); otherwise the mirror loads at the given position. With [deferPlayer] the player reports
     * changes only on [catchUp], as the real MediaController does a moment later.
     */
    override fun setMirrorQueue(songs: List<Song>, index: Int, positionMs: Long, playWhenReady: Boolean, repeatOne: Boolean) {
        calls += "setMirrorQueue"
        mirrorLoads += MirrorLoad(songs.map { it.id }, index, positionMs, playWhenReady, repeatOne)
        val keep = pending.isEmpty() && mirrorSongs.isNotEmpty() && player.value.currentSong?.id == songs.getOrNull(index)?.id
        mirrorSongs = songs
        queue.value = QueueUiState(songs.mapIndexed { i, s -> QueueItemUi(100L + i, s) }, index)
        val song = songs.getOrNull(index)
        if (keep) {
            if (playWhenReady) apply { setPlaying(true) }
            return
        }
        val uid = nextUid++
        apply {
            player.value = player.value.copy(
                currentSong = song, currentEntryUid = uid, durationMs = song?.durationMs ?: 0, isCompleted = false,
            )
            position = positionMs
            setPlaying(playWhenReady && song != null)
        }
    }
    override fun setMirrorRate(speed: Float, pitch: Float) { mirrorRates += speed to pitch }

    override fun endMirror(snapshot: QueueSnapshot?) {
        calls += "endMirror"
        restored += snapshot
        mirrorSongs = emptyList()
        setPlaying(false)
    }

    private var nextUid = 1_000L
    var deferPlayer = false
    private val pending = mutableListOf<() -> Unit>()
    private fun apply(change: () -> Unit) { if (deferPlayer) pending += change else change() }

    /** The player reports what was asked of it. */
    fun catchUp() {
        val changes = pending.toList()
        pending.clear()
        changes.forEach { it() }
    }

    override fun play() { calls += "play"; playAt += nowMs(); apply { setPlaying(true) } }
    override fun pause() { calls += "pause"; apply { setPlaying(false) } }
    override fun seekTo(positionMs: Long) { calls += "seekTo"; seeks += positionMs; position = positionMs }
    override fun togglePlayPause() { calls += "togglePlayPause" }
    override fun skipToNext() { calls += "skipToNext" }
    override fun skipToPrevious() { calls += "skipToPrevious" }
    override fun seekBy(deltaMs: Long) { calls += "seekBy" }
    override fun addToQueue(songs: List<Song>) { calls += "addToQueue:" + songs.joinToString { it.id.toString() } }

    override fun playSong(song: Song, list: List<Song>, playlistId: String?) { calls += "playSong" }
    override fun playShuffled(list: List<Song>, playlistId: String?) { calls += "playShuffled" }
    override fun setShuffle(enabled: Boolean) { calls += "setShuffle" }
    override fun toggleShuffle() { calls += "toggleShuffle" }
    override fun cycleRepeat() { calls += "cycleRepeat" }
    override fun playNext(songs: List<Song>) { calls += "playNext" }
    override fun removeFromQueue(entryUid: Long) { calls += "removeFromQueue" }
    override fun moveQueueItem(from: Int, to: Int) { calls += "moveQueueItem" }
    override fun insertQueueItem(songId: Long, logicalIndex: Int) { calls += "insertQueueItem" }
    override fun skipToQueueItem(entryUid: Long) { calls += "skipToQueueItem:$entryUid" }
    override fun setUserVolume(volume: Float) { calls += "setUserVolume" }
    override fun setCustomSpeed(speed: Float) { effectCalls += "setCustomSpeed" }
    override fun setCustomPitch(pitch: Float) { effectCalls += "setCustomPitch" }
    override fun setMfxPreset(preset: MfxPreset) { effectCalls += "setMfxPreset" }
    override fun setBassBoost(enabled: Boolean) { effectCalls += "setBassBoost" }
    override fun setWarmth(enabled: Boolean) { effectCalls += "setWarmth" }
    override fun setLoFi(enabled: Boolean) { effectCalls += "setLoFi" }
    override fun setCustomEqEnabled(enabled: Boolean) { effectCalls += "setCustomEqEnabled" }
    override fun setCustomEqBandGain(band: Int, gain: Float) { effectCalls += "setCustomEqBandGain" }
    override fun applyEqPreset(preset: EqPreset) { effectCalls += "applyEqPreset" }
    override fun resetAllEffects() { effectCalls += "resetAllEffects" }
    /** Like the service: the player is stopped and its queue cleared. */
    override fun closePlayer() {
        calls += "closePlayer"
        queue.value = QueueUiState()
        player.value = player.value.copy(currentSong = null, isPlaying = false)
    }
    override fun undoClosePlayer() { calls += "undoClosePlayer" }
    override fun startSleepTimer(durationMs: Long, finishSong: Boolean, fadeSeconds: Int) { calls += "sleep" }
    override fun startSleepTimerTracks(count: Int, fadeSeconds: Int) { calls += "sleep" }
    override fun extendSleepTimer(deltaMs: Long) { calls += "sleep" }
    override fun cancelSleepTimer() { calls += "sleep" }
}

class FakeHostTransport : JamHostTransport {
    val eventChannel = Channel<JamEvent>(Channel.UNLIMITED)
    override val events: Flow<JamEvent> = eventChannel.receiveAsFlow()
    override val codeExhausted: StateFlow<Boolean> = MutableStateFlow(false)
    val broadcasts = mutableListOf<JamFrame>()
    val sent = mutableListOf<Pair<String, JamFrame>>()
    val kicked = mutableListOf<String>()
    val codes = mutableListOf<String>()
    var stopped = false

    /** When set, start waits for it. */
    var startGate: CompletableDeferred<Unit>? = null
    override suspend fun start(code: String): Int { startGate?.await(); codes += code; return 41_234 }
    override fun broadcast(frame: JamFrame) { broadcasts += frame }
    override fun sendTo(guestId: String, frame: JamFrame) { sent += guestId to frame }
    override fun kick(guestId: String) { kicked += guestId }
    override fun replaceCode(code: String) { codes += code }
    override fun stop() { stopped = true; eventChannel.close() }
}

class FakeLink(offsetMs: Long? = null) : JamGuestLink {
    private val channel = Channel<JamFrame>(Channel.UNLIMITED)
    override val frames: Flow<JamFrame> = channel.receiveAsFlow()
    override val clock = ClockSync().apply { offsetMs?.let { addSample(sentMs = 0, hostMs = it, receivedMs = 0) } }
    val sent = mutableListOf<JamFrame>()
    var closed = false
    /** Frames the outbox still takes (null: all); a refused frame is not recorded in [sent]. */
    var accepts: Int? = null

    fun deliver(frame: JamFrame) { channel.trySend(frame) }

    /** The connection broke (host gone or Wi-Fi hiccup). */
    fun drop() { channel.close(JamException(JamError.HostGone)) }
    override fun send(frame: JamFrame): Boolean {
        val left = accepts
        if (left != null) {
            if (left <= 0) return false
            accepts = left - 1
        }
        sent += frame
        return true
    }
    override fun close() { closed = true; channel.close() }
}

class FakeConnector : JamGuestConnector {
    val links = ArrayDeque<FakeLink>()
    /** Each reconnect attempt takes the next answer: a link, or an error to throw. */
    val reconnects = ArrayDeque<Any>()
    var reconnectAttempts = 0
    var joinError: JamError? = null
    /** When set, join waits for it (a slow handshake). */
    var joinGate: CompletableDeferred<Unit>? = null

    override suspend fun join(host: String, port: Int, code: String, name: String): JamGuestLink {
        joinGate?.await()
        joinError?.let { throw JamException(it) }
        return links.removeFirst()
    }

    override suspend fun reconnect(host: String, port: Int, dropped: JamGuestLink, name: String): JamGuestLink {
        reconnectAttempts++
        return when (val next = reconnects.removeFirstOrNull() ?: JamError.HostGone) {
            is FakeLink -> next
            is JamError -> throw JamException(next)
            else -> error("unexpected $next")
        }
    }
}
