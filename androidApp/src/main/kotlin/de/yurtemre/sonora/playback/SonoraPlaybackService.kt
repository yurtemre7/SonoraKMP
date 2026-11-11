package de.yurtemre.sonora.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import de.yurtemre.sonora.MainActivity
import de.yurtemre.sonora.R
import de.yurtemre.sonora.platform.MediaVolume
import de.yurtemre.sonora.playback.SessionCommands as C
import de.yurtemre.sonora.playback.sleep.SleepTimer
import de.yurtemre.sonora.playback.sleep.SleepTimerHost
import de.yurtemre.sonora.playback.sleep.SleepTimerNotifier
import de.yurtemre.sonora.shared.audio.EqGains
import de.yurtemre.sonora.shared.audio.SpeedPresets
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.model.EqPreset
import de.yurtemre.sonora.shared.model.MfxPreset
import de.yurtemre.sonora.shared.model.QueueEntry
import de.yurtemre.sonora.shared.model.QueueState
import de.yurtemre.sonora.shared.model.RepeatMode
import de.yurtemre.sonora.shared.queue.add
import de.yurtemre.sonora.shared.queue.cleared
import de.yurtemre.sonora.shared.queue.currentEntry
import de.yurtemre.sonora.shared.queue.cycleRepeat
import de.yurtemre.sonora.shared.queue.insertAt
import de.yurtemre.sonora.shared.queue.load
import de.yurtemre.sonora.shared.queue.move
import de.yurtemre.sonora.shared.queue.playNext
import de.yurtemre.sonora.shared.queue.quickShuffle
import de.yurtemre.sonora.shared.queue.removeByUid
import de.yurtemre.sonora.shared.queue.skipTo
import de.yurtemre.sonora.shared.queue.toggleShuffle
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.settings.PlaybackStateStore
import de.yurtemre.sonora.shared.settings.SavedPlaybackState
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.widget.WidgetActions
import de.yurtemre.sonora.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Media3 playback service (PLAY-01, 20–24, 28; WID-*). No Android Auto browsing (D-52). */
@OptIn(UnstableApi::class)
class SonoraPlaybackService : MediaLibraryService(), KoinComponent {
    private val settingsRepo: SettingsRepository by inject()
    private val library: LibraryRepository by inject()
    private val stats: StatsRepository by inject()
    private val stateStore: PlaybackStateStore by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var exo: ExoPlayer
    private lateinit var player: SonoraForwardingPlayer
    private lateinit var queue: QueueController
    private lateinit var effects: AudioEffects
    private lateinit var focus: AudioFocusController
    private lateinit var noisy: NoisyAndReconnectHandler
    private lateinit var recorder: ListenStatsRecorder
    private lateinit var persistence: PlaybackPersistence
    private lateinit var widget: WidgetUpdater
    private lateinit var sleepTimer: SleepTimer
    private var session: MediaLibrarySession? = null
    private var settings = AppSettings()
    private var restoreJob: Job? = null
    /** A user load happened; a pending restore must not override it. */
    private var userLoaded = false
    /** Items that failed in a row without anything playing in between (error skip, see [handlePlayerError]). */
    private var consecutiveErrors = 0
    private var lastError: C.PlaybackErrorEvent? = null

    private val host = object : SleepTimerHost {
        override val isPlaying: Boolean get() = exo.isPlaying
        override val currentSongId: Long? get() = queue.state.value.currentEntry?.songId
        override val positionMs: Long get() = exo.currentPosition
        override val durationMs: Long
            get() = exo.duration.takeIf { it > 0 } ?: currentSongId?.let(queue::cachedSong)?.durationMs ?: 0L
        override val userVolume: Double get() = player.volumes.userVolume
        override fun applyVolume(volume: Double) { player.volumes.userVolume = volume }
        override fun pauseImmediate() = player.pauseImmediately()
        override fun finalizeStop() = player.pause()
        override fun updateControls(active: Boolean) = updateButtons(active)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) queue.onEnded()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) consecutiveErrors = 0
            persistence.onIsPlayingChanged(isPlaying)
        }
        override fun onPlayerError(error: PlaybackException) = handlePlayerError(error)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                MediaVolume.ensureAudible(this@SonoraPlaybackService)
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) noisy.clearDisconnectFlag()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        MediaVolume.ensureAudible(this) // PLAY-28
        exo = PlayerFactory.create(this, handleAudioFocus = true) // AudioFocusController switches per pause_on_duck
        player = SonoraForwardingPlayer(exo, scope)
        queue = QueueController(player, scope, songs = { ids ->
            val wanted = ids.toSet()
            library.songs.first().filter { it.id in wanted }.associateBy { it.id }
        })
        recorder = ListenStatsRecorder(player, stats, scope, songIdOf = { item ->
            item.mediaId.removePrefix(QueueController.ID_PREFIX).toLongOrNull()
                ?.let { uid -> queue.state.value.entries.firstOrNull { it.uid == uid }?.songId }
        })
        // All transport routes (UI, notification, Bluetooth, widget) record skip/restart stats.
        player.navigator = object : QueueNavigator {
            override fun skipNext() { recorder.onSkipNext(); queue.skipNext() }
            override fun skipPrevious(positionMs: Long) { recorder.onPrevious(); queue.skipPrevious(positionMs) }
        }
        persistence = PlaybackPersistence(
            stateStore, scope, snapshot = ::snapshot, positionMs = { exo.currentPosition },
            restoreEnabled = { settingsRepo.settings.first().restoreLastPlayedSong },
        )
        effects = AudioEffects(exo, settingsRepo.settings, scope).also { it.start() }
        focus = AudioFocusController(this, exo, settingsRepo.settings, scope).also { it.start() }
        noisy = NoisyAndReconnectHandler(this, exo, settingsRepo.settings, scope, resume = { player.play() })
            .also { it.start() }
        widget = WidgetUpdater(this, player)
        sleepTimer = SleepTimer(scope, host, SleepTimerNotifier(this, ComponentName(this, SonoraPlaybackService::class.java)))

        exo.addListener(playerListener)
        player.addListener(widget)
        queue.onCurrentChanged = { effects.reapplySpeed(); persistence.onTrackChanged() }

        session = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_launcher_monochrome) },
        )
        updateButtons(false)
        recorder.start()

        scope.launch {
            settingsRepo.settings.first().let {
                player.volumes.userVolume = it.volume
                queue.restoreRepeat(it.repeatMode)
            }
            settingsRepo.settings.collect { settings = it; queue.replayGainMode = it.replayGainMode }
        }
        scope.launch { queue.state.collect { publishExtras() } }
        scope.launch { sleepTimer.uiState.collect { publishExtras() } }
        scope.launch { effects.eqInfo.collect { publishExtras() } }
        scope.launch { effects.isEqualizerSupported.collect { publishExtras() } }
        restoreJob = scope.launch { restore() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            SleepTimerNotifier.ACTION_EXTEND -> extendTimer(SLEEP_EXTEND_SEC)
            SleepTimerNotifier.ACTION_END -> sleepTimer.stop()
            in WidgetActions.ALL -> {
                // Started via getForegroundService: must enter the foreground even if nothing ends up playing.
                session?.let { onUpdateNotification(it, true) }
                scope.launch {
                    restoreJob?.join()
                    when (action) {
                        WidgetActions.ACTION_PLAY_PAUSE -> if (exo.playWhenReady) player.pause() else player.play()
                        WidgetActions.ACTION_NEXT -> player.seekToNext()
                        WidgetActions.ACTION_PREVIOUS -> player.seekToPrevious()
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** Swipe from recents: never clears the saved session; stops only when "keep playing on close" is off. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (settings.keepPlayingOnClose) {
            super.onTaskRemoved(rootIntent)
        } else {
            persistence.saveNow()
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        persistence.saveNow()
        widget.markStopped()
        sleepTimer.release()
        recorder.release()
        effects.stop()
        focus.stop()
        noisy.stop()
        queue.release()
        session?.release()
        session = null
        exo.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---- session callback ----

    private val callback = object : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val trusted = controller.packageName == packageName
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            (if (trusted) C.ALL else C.PUBLIC).forEach { cmds.add(C.command(it)) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(cmds.build())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            handle(customCommand.customAction, args)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                restoreJob?.join()
                if (exo.mediaItemCount == 0) restore()
                val items = List(exo.mediaItemCount) { exo.getMediaItemAt(it) }
                if (items.isEmpty()) result.setException(UnsupportedOperationException("nothing to resume"))
                else result.set(MediaSession.MediaItemsWithStartPosition(items, exo.currentMediaItemIndex, exo.currentPosition))
            }
            return result
        }
    }

    private fun handle(action: String, a: Bundle) {
        val s = queue.state.value
        when (action) {
            C.SET_QUEUE -> {
                val ids = a.getLongArray(C.ARG_IDS)?.toList().orEmpty()
                val playlist = a.getString(C.ARG_PLAYLIST)
                userLoaded = true
                val ns = if (a.getBoolean(C.ARG_SHUFFLE)) {
                    recorder.onShuffleSessionStarted()
                    s.quickShuffle(ids, playlistContext = playlist)
                } else {
                    s.load(ids, a.getLong(C.ARG_START_SONG, -1L).takeIf { it >= 0 }, playlist)
                }
                queue.setQueue(ns)
            }
            C.RESTORE_QUEUE -> C.decodeQueue(a.getString(C.ARG_QUEUE_JSON))?.let {
                userLoaded = true
                queue.setQueue(it, a.getLong(C.ARG_POSITION), playWhenReady = false)
            }
            C.PLAY_NEXT -> queue.applyEdit(s.playNext(a.getLongArray(C.ARG_IDS)?.toList().orEmpty()))
            C.ADD_TO_QUEUE -> queue.applyEdit(s.add(a.getLongArray(C.ARG_IDS)?.toList().orEmpty()))
            C.REMOVE -> queue.applyEdit(s.removeByUid(a.getLong(C.ARG_UID)))
            C.MOVE -> queue.applyEdit(s.move(a.getInt(C.ARG_FROM), a.getInt(C.ARG_TO)))
            C.INSERT_AT -> queue.applyEdit(s.insertAt(a.getLong(C.ARG_SONG), a.getInt(C.ARG_TO)))
            C.SKIP_TO -> {
                val idx = s.entries.indexOfFirst { it.uid == a.getLong(C.ARG_UID) }
                if (idx >= 0) queue.applyEdit(s.skipTo(idx))
            }
            C.SET_SHUFFLE -> if (a.getBoolean(C.ARG_ENABLED) != s.isShuffled) toggleShuffle(s)
            C.TOGGLE_SHUFFLE -> toggleShuffle(s)
            C.CYCLE_REPEAT -> {
                val next = s.cycleRepeat()
                queue.applyEdit(next)
                persist { setRepeatMode(next.repeat) }
            }
            C.SKIP_NEXT -> player.seekToNext()
            C.SKIP_PREVIOUS -> player.seekToPrevious()
            C.SET_VOLUME -> {
                val v = a.getFloat(C.ARG_VALUE).toDouble().coerceIn(0.0, 1.0)
                player.volumes.userVolume = v
                persist { setVolume(v) }
            }
            C.SET_SPEED -> persist { setSpeed(SpeedPresets.clampSpeed(a.getFloat(C.ARG_VALUE).toDouble())) }
            C.SET_MFX_PRESET -> persist {
                setMfxPreset(SpeedPresets.normalize(MfxPreset.fromStorage(a.getString(C.ARG_NAME))))
            }
            C.SET_BASS_BOOST -> setEqToggle(bass = a.getBoolean(C.ARG_ENABLED))
            C.SET_WARMTH -> setEqToggle(warmth = a.getBoolean(C.ARG_ENABLED))
            C.SET_LOFI -> setEqToggle(lofi = a.getBoolean(C.ARG_ENABLED))
            C.SET_CUSTOM_EQ -> setEqToggle(custom = a.getBoolean(C.ARG_ENABLED))
            C.SET_EQ_BAND -> {
                val n = effects.bandCount.value
                val band = a.getInt(C.ARG_BAND)
                if (band in 0 until n) {
                    val gains = (settings.mfxCustomEqGains ?: List(n) { 0.0 }).toMutableList()
                    while (gains.size < n) gains.add(0.0)
                    gains[band] = a.getFloat(C.ARG_VALUE).toDouble()
                    persist { setMfxCustomEqGains(gains) }
                    setEqToggle(custom = true) // moving a band switches custom EQ on (player_mfx.dart:172)
                }
            }
            C.APPLY_EQ_PRESET -> effects.eqInfo.value?.let { eq ->
                val preset = EqPreset.fromStorage(a.getString(C.ARG_NAME))
                val gains = EqGains.presetGains(preset, eq.centersHz.size, eq.minMb / 100.0, eq.maxMb / 100.0)
                persist { setMfxCustomEqGains(gains) }
                setEqToggle(custom = true)
            }
            C.RESET_EFFECTS -> persist {
                setSpeed(1.0); setMfxPreset(null)
                setMfxBassBoostEnabled(false); setMfxReverbEnabled(false); setMfxLofiEnabled(false)
                setMfxCustomEqEnabled(false); setMfxCustomEqGains(null)
            }
            C.SLEEP_START -> sleepTimer.start(a.getLong(C.ARG_SECONDS), a.getBoolean(C.ARG_FINISH_SONG), a.getInt(C.ARG_FADE))
            C.SLEEP_EXTEND -> extendTimer(a.getLong(C.ARG_SECONDS, SLEEP_EXTEND_SEC))
            C.SLEEP_STOP -> sleepTimer.stop()
            C.CLOSE_PLAYER -> closePlayer()
        }
    }

    private fun toggleShuffle(s: QueueState) {
        val ns = s.toggleShuffle()
        if (ns.isShuffled) recorder.onShuffleSessionStarted()
        queue.applyEdit(ns)
    }

    /** One EQ effect at a time: enabling one disables the others. */
    /**
     * PLAY-14 (player_mfx.dart:142-180): bass boost, warmth and lo-fi exclude each other but leave
     * custom EQ as is (precedence decides what is heard); enabling custom EQ turns the three off.
     */
    private fun setEqToggle(bass: Boolean? = null, warmth: Boolean? = null, lofi: Boolean? = null, custom: Boolean? = null) =
        persist {
            val s = this@SonoraPlaybackService.settings
            val presetOn = listOf(bass, warmth, lofi).any { it == true }
            val clearPresets = presetOn || custom == true
            setMfxBassBoostEnabled(bass ?: (if (clearPresets) false else s.mfxBassBoostEnabled))
            setMfxReverbEnabled(warmth ?: (if (clearPresets) false else s.mfxReverbEnabled))
            setMfxLofiEnabled(lofi ?: (if (clearPresets) false else s.mfxLofiEnabled))
            setMfxCustomEqEnabled(custom ?: s.mfxCustomEqEnabled)
        }

    private fun persist(block: suspend SettingsRepository.() -> Unit) {
        scope.launch { settingsRepo.block() }
    }

    private fun extendTimer(sec: Long) =
        sleepTimer.extend(sec, settings.sleepTimerFinishSong, settings.sleepTimerFadeOutSeconds)

    /** PLAY-21: clear queue + saved session + widget; Media3 drops the foreground notification once idle/empty. */
    private fun closePlayer() {
        sleepTimer.stop()
        player.pauseImmediately()
        queue.setQueue(queue.state.value.cleared(), 0, playWhenReady = false)
        exo.stop()
        persistence.clear()
        widget.clear()
    }

    private fun updateButtons(timerActive: Boolean) {
        session?.setMediaButtonPreferences(NotificationButtons.build(this, timerActive))
    }

    private fun publishExtras() {
        val s = session ?: return
        s.setSessionExtras(
            C.toBundle(
                C.Extras(
                    queue.state.value, sleepTimer.uiState.value, effects.isEqualizerSupported.value, effects.eqInfo.value,
                    lastError,
                ),
            ),
        )
    }

    // ---- errors ----

    /**
     * An unplayable item (I/O, missing file, unparsable or undecodable media) is skipped instead of stopping
     * playback (departs from Flutter). Playback stops only once every item in the loaded window failed in a row.
     */
    private fun handlePlayerError(error: PlaybackException) {
        val songId = queue.state.value.currentEntry?.songId
        val skippable = error.errorCode in 2000..4999 // IO_*, PARSING_*, DECODING_* / DECODER_*
        consecutiveErrors++
        val q = queue.state.value
        // Last logical item: only repeat-all wraps; otherwise this ends like a normal queue end (queue kept).
        val atEnd = q.currentIndex >= q.entries.size - 1 && q.repeat != RepeatMode.All
        val stop = !skippable || atEnd || consecutiveErrors >= exo.mediaItemCount.coerceAtLeast(1)
        Log.w(TAG, "Playback error ${error.errorCodeName} on song $songId (${consecutiveErrors} in a row, " +
            if (stop) "stopping)" else "skipping)", error)
        lastError = C.PlaybackErrorEvent((lastError?.id ?: 0L) + 1, songId, error.errorCode, stop)
        publishExtras()
        if (stop) {
            consecutiveErrors = 0
            player.pauseImmediately()
            return
        }
        // Next in the window; past the window edge the queue reloads around the next logical item (repeat-all wraps).
        val next = exo.currentMediaItemIndex + 1
        if (next < exo.mediaItemCount) exo.seekTo(next, 0)
        else if (q.repeat == RepeatMode.All && exo.mediaItemCount == q.entries.size) exo.seekTo(0, 0)
        else queue.skipNext()
        exo.prepare()
        exo.playWhenReady = true
    }

    // ---- persistence ----

    private fun snapshot(): SavedPlaybackState? {
        val s = queue.state.value
        val cur = s.currentEntry ?: return null
        return SavedPlaybackState(
            songId = cur.songId,
            queueIds = s.entries.map { it.songId },
            originalQueueIds = s.original.map { it.songId },
            positionMs = exo.currentPosition,
            isShuffled = s.isShuffled,
        )
    }

    /** PLAY-24: restore the last session paused at its position (only into an empty queue). */
    private suspend fun restore() {
        if (queue.state.value.entries.isNotEmpty() || userLoaded) return
        recorder.pauseRecording()
        try {
            val saved = persistence.loadForRestore() ?: return
            val known = library.songs.first().mapTo(HashSet()) { it.id }
            if (saved.songId !in known || userLoaded) return
            val ids = saved.queueIds.filter { it in known }.ifEmpty { listOf(saved.songId) }
            val orig = saved.originalQueueIds.filter { it in known }.ifEmpty { ids }
            val base = QueueState(repeat = settingsRepo.settings.first().repeatMode).load(orig, saved.songId)
            var next = base.nextUid
            val state = if (saved.isShuffled) {
                val pool = HashMap<Long, ArrayDeque<QueueEntry>>()
                base.original.forEach { pool.getOrPut(it.songId) { ArrayDeque() }.addLast(it) }
                val entries = ids.map { id -> pool[id]?.removeFirstOrNull() ?: QueueEntry(next++, id) }
                base.copy(
                    entries = entries, isShuffled = true, nextUid = next,
                    currentIndex = entries.indexOfFirst { it.songId == saved.songId }.coerceAtLeast(0),
                )
            } else base
            queue.setQueue(state, saved.positionMs, playWhenReady = false).join()
        } finally {
            recorder.resumeRecording()
        }
    }

    companion object {
        const val CHANNEL_ID = "playback"
        const val SLEEP_EXTEND_SEC = 60L
        private const val TAG = "SonoraPlayback"
    }
}
