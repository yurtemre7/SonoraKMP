package de.yurtemre.sonora.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import de.yurtemre.sonora.ui.format.withAppLocale
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import de.yurtemre.sonora.jam.JamController
import de.yurtemre.sonora.jam.JamGuestActions
import de.yurtemre.sonora.jam.JamUiState
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
import de.yurtemre.sonora.shared.library.TitleFormatter
import de.yurtemre.sonora.shared.audio.EqGains
import de.yurtemre.sonora.shared.audio.SpeedPresets
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.model.EqPreset
import de.yurtemre.sonora.shared.model.MfxPreset
import de.yurtemre.sonora.shared.model.QueueEntry
import de.yurtemre.sonora.shared.model.QueueState
import de.yurtemre.sonora.shared.model.RepeatMode
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.queue.add
import de.yurtemre.sonora.shared.queue.cleared
import de.yurtemre.sonora.shared.queue.currentEntry
import de.yurtemre.sonora.shared.queue.cycleRepeat
import de.yurtemre.sonora.shared.queue.insertAt
import de.yurtemre.sonora.shared.queue.isLast
import de.yurtemre.sonora.shared.queue.load
import de.yurtemre.sonora.shared.queue.move
import de.yurtemre.sonora.shared.queue.playNext
import de.yurtemre.sonora.shared.queue.quickShuffle
import de.yurtemre.sonora.shared.queue.removeByUid
import de.yurtemre.sonora.shared.queue.SmartShuffle
import de.yurtemre.sonora.shared.queue.skipTo
import de.yurtemre.sonora.shared.queue.toggleShuffle
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.SongStats
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val queueBus: QueueStateBus by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var exo: ExoPlayer
    private lateinit var player: SonoraForwardingPlayer
    private lateinit var queue: QueueController
    private lateinit var effects: AudioEffects
    private lateinit var fader: TransitionFader
    private lateinit var focus: AudioFocusController
    private lateinit var noisy: NoisyAndReconnectHandler
    private lateinit var recorder: ListenStatsRecorder
    private lateinit var persistence: PlaybackPersistence
    private lateinit var widget: WidgetUpdater
    private lateinit var sleepTimer: SleepTimer
    private var session: MediaLibrarySession? = null
    private var settings = AppSettings()
    private var songIndexState: StateFlow<Map<Long, Song>?> = MutableStateFlow(null)
    private var statsState: StateFlow<Map<Long, SongStats>?> = MutableStateFlow(null)
    private var restoreJob: Job? = null
    /** A user load happened; a pending restore must not override it. */
    private var userLoaded = false
    /** Items that failed in a row without anything playing in between (error skip, see [handlePlayerError]). */
    private var consecutiveErrors = 0
    private var lastError: C.PlaybackErrorEvent? = null
    /** Jam guest mode: a mirrored queue is loaded (see [setMirror]); never persisted. */
    private lateinit var mirrorMode: JamMirrorMode
    private val mirroring: Boolean get() = mirrorMode.active

    private val host = object : SleepTimerHost {
        override val isPlaying: Boolean get() = exo.isPlaying
        override val currentSongId: Long? get() = queue.state.value.currentEntry?.songId
        override val currentEntryId: Long? get() = queue.state.value.currentEntry?.uid
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
            if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) noisy.clearDisconnectFlag()
        }
    }

    override fun onCreate() {
        super.onCreate()
        MediaVolume.ensureAudible(this) // PLAY-28
        exo = PlayerFactory.create(this, handleAudioFocus = true) // AudioFocusController switches per pause_on_duck
        player = SonoraForwardingPlayer(exo, scope)
        // Hot id → song index: a queue load must not re-read the whole song table (cold Room flow) per tap.
        val songIndex = library.songs.map { l -> l.associateBy { it.id } }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, null)
        songIndexState = songIndex
        // Latest stats snapshot for smart shuffle; handle() is not suspending, so it reads the cached value.
        statsState = stats.snapshot.map { it.songs }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)
        queue = QueueController(player, scope, songs = { ids ->
            val index = songIndex.filterNotNull().first()
            buildMap { ids.forEach { id -> index[id]?.let { put(id, it) } } }
        }, displayTitle = { // LIB-14: notification, lock screen and Auto show the filtered title
            TitleFormatter.displayTitle(
                it.title,
                it.artist,
                settings.filterTitleFeatures,
                settings.filterTitleArtist,
                settings.filterTitleVideoTags,
            )
        })
        recorder = ListenStatsRecorder(player, stats, scope, songIdOf = { item ->
            item.mediaId.removePrefix(QueueController.ID_PREFIX).toLongOrNull()
                ?.let { uid -> queue.state.value.entries.firstOrNull { it.uid == uid }?.songId }
        })
        // All transport routes (UI, notification, Bluetooth, widget) record skip/restart stats.
        player.navigator = object : QueueNavigator {
            // A Jam guest follows the host: local skips (notification, Bluetooth, widget) would diverge, so they
            // become requests to the host when it allows guests to skip, and are ignored otherwise. (The app's own
            // UI never gets here while mirroring: JamGuardedPlayback sends its requests directly.)
            override fun skipNext() {
                if (mirroring) return jamGuestRequest { guest, actions -> actions.next(guest) }
                recorder.onSkipNext(queue.state.value.isLast); queue.skipNext()
            }
            override fun skipPrevious(positionMs: Long) {
                if (mirroring) return jamGuestRequest { guest, actions -> actions.previous(guest) }
                recorder.onPrevious(); queue.skipPrevious(positionMs)
            }
        }
        persistence = PlaybackPersistence(
            stateStore, scope, snapshot = ::snapshot, positionMs = { exo.currentPosition },
            restoreEnabled = { settingsRepo.settings.first().restoreLastPlayedSong },
        )
        fader = TransitionFader(exo, player.volumes, scope)
        effects = AudioEffects(exo, settingsRepo.settings, scope).also { it.start() }
        mirrorMode = JamMirrorMode(persistence, setNormalSpeed = { effects.normalSpeed = it }, setSkipSilence = { exo.skipSilenceEnabled = it })
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
            DefaultMediaNotificationProvider.Builder(this.withAppLocale())
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
            settingsRepo.settings.collect {
                settings = it
                queue.replayGainMode = it.replayGainMode
                mirrorMode.userSkipSilence = it.skipSilence // stays off while a Jam mirror is loaded
                fader.setFadeSeconds(it.fadeBetweenSongsSeconds)
            }
        }
        scope.launch { queue.state.collect { queueBus.state.value = it } }
        scope.launch { sleepTimer.uiState.collect { publishExtras() } }
        scope.launch { queue.state.map { it.currentEntry?.uid }.distinctUntilChanged().collect { sleepTimer.onEntryChanged() } }
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
                        WidgetActions.ACTION_PLAY_PAUSE -> when {
                            mirroring -> jamGuestRequest { guest, actions -> actions.playPause(guest) } // the host decides
                            exo.playWhenReady -> player.pause()
                            else -> player.play()
                        }
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
        fader.stop()
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

    /** Runs [request] with the Jam guest state, if this phone is a guest (resolved lazily: no cycle at start-up). */
    private fun jamGuestRequest(request: (JamUiState.Joined, JamGuestActions) -> Unit) {
        val jam = getKoin().getOrNull<JamController>() ?: return
        val guest = jam.state.value as? JamUiState.Joined ?: return
        request(guest, JamGuestActions(jam))
    }

    private fun isExternal(controller: MediaSession.ControllerInfo): Boolean =
        isExternalController(controller.packageName, packageName, session?.isMediaNotificationController(controller) == true)

    /** Outside controllers lose seek and queue commands while mirroring ([externalPlayerCommands]); back after. */
    private fun refreshExternalCommands() {
        val s = session ?: return
        s.connectedControllers.filter(::isExternal).forEach { c ->
            s.setAvailableCommands(c, sessionCommandsFor(c), externalPlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS, mirroring))
        }
    }

    private fun sessionCommandsFor(controller: MediaSession.ControllerInfo): SessionCommands {
        // Own app, system UI (notification and lock screen buttons) and Auto; other apps get playback commands only.
        val trusted = controller.packageName == packageName || controller.isTrusted
        val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        if (trusted) C.ALL.forEach { cmds.add(C.command(it)) } // untrusted apps keep the default playback commands only
        return cmds.build()
    }

    // ---- session callback ----

    private val callback = object : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommandsFor(controller))
            if (mirroring && isExternal(controller)) {
                builder.setAvailablePlayerCommands(externalPlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS, true))
            }
            return builder.build()
        }

        override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int =
            when (jamExternalCommand(playerCommand, mirroring, isExternal(controller))) {
                ExternalCommand.Allow -> super.onPlayerCommandRequest(session, controller, playerCommand)
                ExternalCommand.RequestPlayPause -> {
                    jamGuestRequest { guest, actions -> actions.playPause(guest) }
                    SessionResult.RESULT_ERROR_NOT_SUPPORTED // never a local pause or play that the host would undo
                }
                ExternalCommand.Reject -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
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
        val s = queue.latestState // edits build on a still-pending edit, so quick commands do not drop each other
        when (action) {
            C.SET_QUEUE -> {
                val ids = a.getLongArray(C.ARG_IDS)?.toList().orEmpty()
                val playlist = a.getString(C.ARG_PLAYLIST)
                userLoaded = true
                val ns = if (a.getBoolean(C.ARG_SHUFFLE)) {
                    recorder.onShuffleSessionStarted()
                    s.quickShuffle(ids, playlistContext = playlist, reorder = smartReorder())
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
            C.SET_PITCH -> persist { setPitch(SpeedPresets.clampPitch(a.getFloat(C.ARG_VALUE).toDouble())) }
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
                setSpeed(1.0); setPitch(1.0); setMfxPreset(null)
                setMfxBassBoostEnabled(false); setMfxReverbEnabled(false); setMfxLofiEnabled(false)
                setMfxCustomEqEnabled(false); setMfxCustomEqGains(null)
            }
            C.SLEEP_START -> sleepTimer.start(a.getLong(C.ARG_SECONDS), a.getBoolean(C.ARG_FINISH_SONG), a.getInt(C.ARG_FADE))
            C.SLEEP_START_TRACKS -> sleepTimer.startTracks(a.getInt(C.ARG_COUNT, 1), a.getInt(C.ARG_FADE))
            C.SLEEP_EXTEND -> extendTimer(a.getLong(C.ARG_SECONDS, SLEEP_EXTEND_SEC))
            C.SLEEP_STOP -> sleepTimer.stop()
            C.CLOSE_PLAYER -> closePlayer()
            C.SET_MIRROR -> setMirror(
                a.getLongArray(C.ARG_IDS)?.toList().orEmpty(), a.getInt(C.ARG_INDEX), a.getLong(C.ARG_POSITION),
                a.getBoolean(C.ARG_PLAY), a.getBoolean(C.ARG_REPEAT_ONE),
            )
            C.END_MIRROR -> endMirror(C.decodeQueue(a.getString(C.ARG_QUEUE_JSON)), a.getLong(C.ARG_POSITION))
        }
    }

    /**
     * Smart shuffle ordering for upcoming entries, or null (plain shuffle) when the setting is off. Before the song
     * index has loaded (cold start) it is a plain shuffle too ([SmartShuffle.reorderEntries]).
     */
    private fun smartReorder(): ((List<QueueEntry>) -> List<QueueEntry>)? {
        if (!settings.smartShuffle) return null
        return { entries ->
            SmartShuffle.reorderEntries(entries, songIndexState.value, statsState.value.orEmpty(), System.currentTimeMillis())
        }
    }

    private fun toggleShuffle(s: QueueState) {
        val ns = s.toggleShuffle(reorder = smartReorder())
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

    /**
     * PLAY-21: clear queue + saved session + widget; Media3 drops the foreground notification once idle/empty.
     * During a Jam mirror the saved session is the guest's own queue: it is kept (it comes back on the next start),
     * the mirror mode ends here, and the Jam controller sees the emptied queue and leaves the Jam.
     */
    private fun closePlayer() {
        sleepTimer.stop()
        val stop = {
            player.pauseImmediately()
            queue.setQueue(queue.state.value.cleared(), 0, playWhenReady = false)
            exo.stop()
        }
        if (mirroring) {
            mirrorMode.end(stop) // stops while still in mirror mode: the pause's save cannot store the Jam queue
            refreshExternalCommands()
        } else {
            stop()
            persistence.clear()
        }
        widget.clear()
    }

    /**
     * Jam guest mirror: the first call saves the guest's own session once, then persistence pauses (an app killed
     * mid-Jam restores the guest's own queue). Repeat off (one with [repeatOne]), normal speed, skip silence off. When [ids] keeps the
     * playing entry current it stays loaded (no gap) and [play] is honoured; otherwise the mirror loads at
     * [positionMs], paused unless [play].
     */
    private fun setMirror(ids: List<Long>, index: Int, positionMs: Long, play: Boolean, repeatOne: Boolean) {
        val starting = !mirroring
        if (starting) {
            userLoaded = true
            sleepTimer.stop()
        }
        val edit = mirrorEdit(queue.state.value, queue.latestState, mirroring, ids, index, positionMs, play, repeatOne)
        mirrorMode.begin {
            when (edit) {
                is MirrorEdit.Keep -> {
                    queue.applyEdit(edit.state)
                    if (edit.play && !exo.playWhenReady) player.play()
                }
                is MirrorEdit.Load -> {
                    if (!edit.play) player.pauseImmediately() // a paused start: never let the old item play on meanwhile
                    queue.setQueue(edit.state, edit.positionMs, playWhenReady = edit.play)
                }
            }
        }
        if (starting) refreshExternalCommands()
    }

    /** Ends the Jam mirror: [restored] (the guest's own queue) comes back paused at [positionMs]. */
    private fun endMirror(restored: QueueState?, positionMs: Long) {
        if (!mirroring) return
        mirrorMode.end { player.pauseImmediately() } // pause first: still in mirror mode, still at Jam speed
        refreshExternalCommands()
        if (restored != null) {
            queue.setQueue(restored, positionMs, playWhenReady = false)
        } else {
            queue.setQueue(queue.state.value.cleared(), 0, playWhenReady = false)
            persistence.clear()
        }
    }

    private fun updateButtons(timerActive: Boolean) {
        session?.setMediaButtonPreferences(NotificationButtons.build(this, timerActive))
    }

    private fun publishExtras() {
        val s = session ?: return
        s.setSessionExtras(
            C.toBundle(
                C.Extras(
                    sleepTimer.uiState.value, effects.isEqualizerSupported.value, effects.eqInfo.value,
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
