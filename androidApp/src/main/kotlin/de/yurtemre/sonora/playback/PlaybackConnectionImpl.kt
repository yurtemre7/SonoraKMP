package de.yurtemre.sonora.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.C as M3
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import de.yurtemre.sonora.playback.SessionCommands as C
import de.yurtemre.sonora.playback.api.MfxUiState
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.playback.api.PlayerUiState
import de.yurtemre.sonora.playback.api.QueueItemUi
import de.yurtemre.sonora.playback.api.QueueUiState
import de.yurtemre.sonora.playback.api.SleepTimerUiState
import de.yurtemre.sonora.shared.audio.EqGains
import de.yurtemre.sonora.shared.audio.displayDurationMs
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.model.EqPreset
import de.yurtemre.sonora.shared.model.MfxPreset
import de.yurtemre.sonora.shared.model.QueueState
import de.yurtemre.sonora.shared.model.RepeatMode
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.queue.currentEntry
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * P4-12: [PlaybackConnection] over a [MediaController] bound to [SonoraPlaybackService]. Player state comes from
 * the controller's listener; queue/sleep timer/EQ layout from the session extras; MFX toggles from settings.
 * Commands issued before the controller connects are queued and replayed.
 */
class PlaybackConnectionImpl(
    context: Context,
    library: LibraryRepository,
    settingsRepo: SettingsRepository,
) : PlaybackConnection {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    private var songs: Map<Long, Song> = emptyMap()
    private var settings = AppSettings()
    private var extras = C.Extras()
    private var lastClosed: Pair<QueueState, Long>? = null

    /**
     * Optimistic UI (P5-26): a command's expected result is shown at once and kept until the controller/session
     * reports it (or [OPTIMISTIC_MS] passes), so a stale event in between can't flicker the old state back.
     */
    private class Pending<T>(val value: T, val atMs: Long = android.os.SystemClock.uptimeMillis())
    private var pendingPlaying: Pending<Boolean>? = null
    private var pendingUid: Pending<Long>? = null
    private var pendingShuffle: Pending<Boolean>? = null
    private var pendingRepeat: Pending<RepeatMode>? = null

    /** Returns the pending value while it differs from [actual] and is fresh; clears it otherwise. */
    private fun <T> resolve(p: Pending<T>?, actual: T, clear: () -> Unit): T {
        if (p == null) return actual
        if (p.value == actual || android.os.SystemClock.uptimeMillis() - p.atMs > OPTIMISTIC_MS) { clear(); return actual }
        return p.value
    }

    private val _player = MutableStateFlow(PlayerUiState())
    private val _queue = MutableStateFlow(QueueUiState())
    private val _sleep = MutableStateFlow<SleepTimerUiState>(SleepTimerUiState.Inactive)
    private val _mfx = MutableStateFlow(MfxUiState())
    override val player: StateFlow<PlayerUiState> = _player.asStateFlow()
    override val queue: StateFlow<QueueUiState> = _queue.asStateFlow()
    override val sleepTimer: StateFlow<SleepTimerUiState> = _sleep.asStateFlow()
    override val mfx: StateFlow<MfxUiState> = _mfx.asStateFlow()

    private val ticker = PositionTicker(scope, { controller?.currentPosition ?: 0L }, { controller?.isPlaying == true })
    override val positionMs: Flow<Long> = ticker.positions

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = recompute()
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) = applyExtras(extras)
        override fun onDisconnected(controller: MediaController) {
            this@PlaybackConnectionImpl.controller = null
            recompute()
        }
    }

    init {
        scope.launch { library.songs.collect { list -> songs = list.associateBy { it.id }; recompute() } }
        scope.launch { settingsRepo.settings.collect { settings = it; recomputeMfx() } }
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, e ->
                    when (e) {
                        Lifecycle.Event.ON_START -> ticker.setForeground(true)
                        Lifecycle.Event.ON_STOP -> ticker.setForeground(false)
                        else -> Unit
                    }
                },
            )
            connect()
        }
    }

    private fun connect() {
        if (connecting || controller != null) return
        connecting = true
        val token = SessionToken(app, ComponentName(app, SonoraPlaybackService::class.java))
        val future = MediaController.Builder(app, token).setListener(controllerListener).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            ticker.attach(c)
            applyExtras(c.sessionExtras)
            while (pending.isNotEmpty()) pending.removeFirst()(c)
        }, ContextCompat.getMainExecutor(app))
    }

    private fun withController(block: (MediaController) -> Unit) {
        scope.launch {
            val c = controller
            if (c != null && c.isConnected) block(c) else { pending.addLast(block); controller = null; connect() }
        }
    }

    private fun send(action: String, args: Bundle = Bundle.EMPTY) =
        withController { it.sendCustomCommand(C.command(action), args) }

    private fun applyExtras(b: Bundle) {
        extras = C.fromBundle(b)
        _sleep.value = extras.sleep
        recompute()
        recomputeMfx()
    }

    private fun recompute() {
        val c = controller
        val qs = extras.queue
        val actualCur = qs.currentEntry
        val curUid = resolve(pendingUid, actualCur?.uid ?: -1L) { pendingUid = null }
        val cur = qs.entries.firstOrNull { it.uid == curUid } ?: actualCur
        val curIndex = qs.entries.indexOf(cur).takeIf { it >= 0 } ?: qs.currentIndex
        val song = cur?.let { songs[it.songId] }
        // Media time for both total and position (PositionTicker): never scale by speed.
        val playerMs = c?.duration?.takeIf { it > 0 && it != M3.TIME_UNSET }
        val duration = displayDurationMs(playerMs, song?.durationMs, c?.currentPosition ?: 0L)
        val state = c?.playbackState ?: Player.STATE_IDLE
        val repeat = resolve(pendingRepeat, qs.repeat) { pendingRepeat = null }
        val shuffled = resolve(pendingShuffle, qs.isShuffled) { pendingShuffle = null }
        val playing = resolve(pendingPlaying, c?.isPlaying == true) { pendingPlaying = null }
        val wraps = repeat != RepeatMode.Off
        _player.value = PlayerUiState(
            isConnected = c?.isConnected == true,
            currentSong = song,
            currentEntryUid = cur?.uid,
            isPlaying = playing,
            isBuffering = state == Player.STATE_BUFFERING,
            isCompleted = state == Player.STATE_ENDED,
            durationMs = duration,
            repeat = repeat,
            isShuffled = shuffled,
            hasNext = cur != null && (curIndex < qs.entries.lastIndex || wraps),
            hasPrevious = cur != null && (curIndex > 0 || wraps),
            userVolume = c?.volume ?: settings.volume.toFloat(),
            playlistContext = qs.playlistContext,
        )
        val items = qs.entries.mapNotNull { e -> songs[e.songId]?.let { QueueItemUi(e.uid, it) } }
        _queue.value = QueueUiState(
            items = items,
            currentIndex = cur?.let { e -> items.indexOfFirst { it.entryUid == e.uid } } ?: -1,
            isShuffled = shuffled,
            playlistContext = qs.playlistContext,
        )
    }

    private fun optimisticSkip(offset: Int) {
        val qs = extras.queue
        val base = pendingUid?.value?.let { u -> qs.entries.indexOfFirst { it.uid == u } }?.takeIf { it >= 0 } ?: qs.currentIndex
        if (base !in qs.entries.indices || qs.repeat == RepeatMode.One) return
        var target = base + offset
        if (target !in qs.entries.indices) {
            if (qs.repeat == RepeatMode.Off) return
            target = (target + qs.entries.size) % qs.entries.size
        }
        pendingUid = Pending(qs.entries[target].uid)
        recompute()
    }

    private fun recomputeMfx() {
        val s = settings
        val eq = extras.eq
        val n = eq?.centersHz?.size ?: 0
        _mfx.value = MfxUiState(
            preset = s.mfxPreset ?: MfxPreset.Normal,
            customSpeed = s.speed.toFloat(),
            bassBoost = s.mfxBassBoostEnabled,
            warmth = s.mfxReverbEnabled,
            loFi = s.mfxLofiEnabled,
            customEqEnabled = s.mfxCustomEqEnabled,
            customEqGains = List(n) { (s.mfxCustomEqGains?.getOrNull(it) ?: 0.0).toFloat() },
            eqBandCenterHz = eq?.centersHz.orEmpty(),
            eqMinLevelMb = eq?.minMb ?: 0,
            eqMaxLevelMb = eq?.maxMb ?: 0,
            isEqAvailable = extras.eqSupported && eq != null,
            activeEqMode = EqGains.mode(s.mfxLofiEnabled, s.mfxReverbEnabled, s.mfxBassBoostEnabled, s.mfxCustomEqEnabled),
        )
    }

    private fun ids(list: List<Song>) = list.map { it.id }.toLongArray()

    // ---- commands ----

    override fun playSong(song: Song, list: List<Song>, playlistId: String?) = send(
        C.SET_QUEUE,
        Bundle().apply {
            putLongArray(C.ARG_IDS, ids(list.ifEmpty { listOf(song) })); putLong(C.ARG_START_SONG, song.id)
            putString(C.ARG_PLAYLIST, playlistId)
        },
    )

    override fun playShuffled(list: List<Song>, playlistId: String?) = send(
        C.SET_QUEUE,
        Bundle().apply { putLongArray(C.ARG_IDS, ids(list)); putString(C.ARG_PLAYLIST, playlistId); putBoolean(C.ARG_SHUFFLE, true) },
    )

    override fun setShuffle(enabled: Boolean) = send(C.SET_SHUFFLE, bool(enabled))
    override fun toggleShuffle() {
        pendingShuffle = Pending(!_player.value.isShuffled)
        recompute()
        send(C.TOGGLE_SHUFFLE)
    }

    override fun cycleRepeat() {
        pendingRepeat = Pending(
            when (_player.value.repeat) {
                RepeatMode.Off -> RepeatMode.All
                RepeatMode.All -> RepeatMode.One
                RepeatMode.One -> RepeatMode.Off
            },
        )
        recompute()
        send(C.CYCLE_REPEAT)
    }

    override fun playNext(songs: List<Song>) = send(C.PLAY_NEXT, Bundle().apply { putLongArray(C.ARG_IDS, ids(songs)) })
    override fun addToQueue(songs: List<Song>) = send(C.ADD_TO_QUEUE, Bundle().apply { putLongArray(C.ARG_IDS, ids(songs)) })
    override fun removeFromQueue(entryUid: Long) = send(C.REMOVE, Bundle().apply { putLong(C.ARG_UID, entryUid) })
    override fun moveQueueItem(from: Int, to: Int) =
        send(C.MOVE, Bundle().apply { putInt(C.ARG_FROM, from); putInt(C.ARG_TO, to) })
    override fun insertQueueItem(songId: Long, logicalIndex: Int) =
        send(C.INSERT_AT, Bundle().apply { putLong(C.ARG_SONG, songId); putInt(C.ARG_TO, logicalIndex) })
    override fun skipToQueueItem(entryUid: Long) {
        pendingUid = Pending(entryUid)
        recompute()
        send(C.SKIP_TO, Bundle().apply { putLong(C.ARG_UID, entryUid) })
    }

    override fun play() = withController { it.play() }
    override fun pause() = withController { it.pause() }
    override fun togglePlayPause() {
        if (!_player.value.isCompleted) { pendingPlaying = Pending(!_player.value.isPlaying); recompute() }
        withController { if (it.playWhenReady && it.playbackState != Player.STATE_ENDED) it.pause() else it.play() }
    }

    override fun skipToNext() { optimisticSkip(1); send(C.SKIP_NEXT) }

    /** "Previous" restarts the song after the first seconds (service rule), so only predict it near the start. */
    override fun skipToPrevious() {
        if ((controller?.currentPosition ?: Long.MAX_VALUE) < PREVIOUS_RESTART_MS) optimisticSkip(-1)
        send(C.SKIP_PREVIOUS)
    }
    override fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0)) }
    override fun seekBy(deltaMs: Long) = withController { c ->
        val target = c.currentPosition + deltaMs
        val dur = c.duration.takeIf { it > 0 && it != M3.TIME_UNSET }
        c.seekTo(if (dur != null) target.coerceIn(0, dur) else target.coerceAtLeast(0))
    }

    override fun setUserVolume(volume: Float) = send(C.SET_VOLUME, float(volume))

    override fun setCustomSpeed(speed: Float) = send(C.SET_SPEED, float(speed))
    override fun setMfxPreset(preset: MfxPreset) = send(C.SET_MFX_PRESET, name(preset.storageName))
    override fun setBassBoost(enabled: Boolean) = send(C.SET_BASS_BOOST, bool(enabled))
    override fun setWarmth(enabled: Boolean) = send(C.SET_WARMTH, bool(enabled))
    override fun setLoFi(enabled: Boolean) = send(C.SET_LOFI, bool(enabled))
    override fun setCustomEqEnabled(enabled: Boolean) = send(C.SET_CUSTOM_EQ, bool(enabled))
    override fun setCustomEqBandGain(band: Int, gain: Float) =
        send(C.SET_EQ_BAND, Bundle().apply { putInt(C.ARG_BAND, band); putFloat(C.ARG_VALUE, gain) })
    override fun applyEqPreset(preset: EqPreset) = send(C.APPLY_EQ_PRESET, name(preset.storageName))
    override fun resetAllEffects() = send(C.RESET_EFFECTS)

    override fun closePlayer() {
        scope.launch {
            if (extras.queue.entries.isNotEmpty()) lastClosed = extras.queue to (controller?.currentPosition ?: 0L)
            send(C.CLOSE_PLAYER)
        }
    }

    override fun undoClosePlayer() {
        scope.launch {
            val (q, pos) = lastClosed ?: return@launch
            lastClosed = null
            send(C.RESTORE_QUEUE, Bundle().apply { putString(C.ARG_QUEUE_JSON, C.encodeQueue(q)); putLong(C.ARG_POSITION, pos) })
        }
    }

    override fun startSleepTimer(durationMs: Long, finishSong: Boolean, fadeSeconds: Int) = send(
        C.SLEEP_START,
        Bundle().apply { putLong(C.ARG_SECONDS, durationMs / 1000); putBoolean(C.ARG_FINISH_SONG, finishSong); putInt(C.ARG_FADE, fadeSeconds) },
    )

    override fun extendSleepTimer(deltaMs: Long) = send(C.SLEEP_EXTEND, Bundle().apply { putLong(C.ARG_SECONDS, deltaMs / 1000) })
    override fun cancelSleepTimer() = send(C.SLEEP_STOP)

    private fun bool(v: Boolean) = Bundle().apply { putBoolean(C.ARG_ENABLED, v) }
    private fun float(v: Float) = Bundle().apply { putFloat(C.ARG_VALUE, v) }
    private fun name(v: String) = Bundle().apply { putString(C.ARG_NAME, v) }

    private companion object {
        const val OPTIMISTIC_MS = 1_500L
        const val PREVIOUS_RESTART_MS = 3_000L
    }
}
