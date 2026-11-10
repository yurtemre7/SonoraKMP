package de.yurtemre.sonora.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.stats.ListenSessionTracker
import de.yurtemre.sonora.shared.stats.StatsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bridges Media3 player events to [ListenSessionTracker] (STAT-01) and forwards emitted
 * [StatsEvent]s to [StatsRepository]. Weekday (Sun=0) and local yyyy-MM-dd use the device zone.
 * Call [pauseRecording]/[resumeRecording] around state restore.
 */
class ListenStatsRecorder(
    private val player: Player,
    private val repo: StatsRepository,
    private val scope: CoroutineScope,
    clock: Clock = Clock { System.currentTimeMillis() },
    private val songIdOf: (MediaItem) -> Long? = { it.mediaId.toLongOrNull() },
    private val playlistIdOf: (MediaItem) -> String? = { it.mediaMetadata.extras?.getString(EXTRA_PLAYLIST_ID) },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : Player.Listener {

    private var timerJob: Job? = null
    private var lastPosition = 0L

    private val tracker = ListenSessionTracker(clock, ::schedule, ::forward)

    fun start() {
        player.addListener(this)
        itemStarted(player.currentMediaItem, player.currentPosition)
        tracker.onSpeedChanged(player.playbackParameters.speed, player.currentPosition)
        tracker.onPlayingChanged(player.isPlaying, player.currentPosition)
    }

    fun release() { player.removeListener(this); timerJob?.cancel() }

    fun pauseRecording() { tracker.pauseRecording(); repo.pauseRecording() }
    fun resumeRecording() { tracker.resumeRecording(); repo.resumeRecording() }

    /** Explicit user actions the player cannot distinguish (P4-11 calls these). */
    fun onSkipNext() = tracker.onSkipNext(isLastItem = !player.hasNextMediaItem())
    fun onPrevious() = tracker.onPrevious(player.currentPosition)
    fun onShuffleSessionStarted() = tracker.onShuffleSessionStarted()

    override fun onIsPlayingChanged(isPlaying: Boolean) = tracker.onPlayingChanged(isPlaying, player.currentPosition)

    override fun onPlaybackParametersChanged(params: PlaybackParameters) =
        tracker.onSpeedChanged(params.speed, player.currentPosition)

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        itemStarted(mediaItem, player.currentPosition)
        tracker.onPlayingChanged(player.isPlaying, player.currentPosition)
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY) {
            // Duration becomes known once ready.
            tracker.onTimerFired(player.currentPosition)
        }
        if (state == Player.STATE_ENDED) tracker.onTimerFired(player.duration.coerceAtLeast(player.currentPosition))
    }

    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && old.mediaItemIndex == new.mediaItemIndex) {
            tracker.onSeek(old.positionMs, new.positionMs)
        }
        lastPosition = new.positionMs
    }

    private fun itemStarted(item: MediaItem?, positionMs: Long) {
        val dur = player.duration.takeIf { it > 0 } ?: (item?.mediaMetadata?.durationMs ?: 0L)
        tracker.onItemStarted(item?.let(songIdOf), dur, item?.let(playlistIdOf), positionMs)
    }

    private fun schedule(delayMs: Long?) {
        timerJob?.cancel()
        timerJob = delayMs?.let { d ->
            scope.launch { delay(d); tracker.onTimerFired(player.currentPosition) }
        }
    }

    private fun forward(event: StatsEvent) {
        scope.launch {
            when (event) {
                is StatsEvent.RecordRecent -> repo.recordRecent(event.songId, event.atMs)
                is StatsEvent.RecordComplete -> {
                    val date = Instant.ofEpochMilli(event.atMs).atZone(zone()).toLocalDate()
                    repo.recordCompleteListen(
                        event.songId, event.atMs,
                        weekday = date.dayOfWeek.value % 7, // Sun=0
                        localDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        playlistId = event.playlistId,
                    )
                }
                is StatsEvent.RecordSkip -> repo.recordSkip(event.songId)
                is StatsEvent.RecordRestart -> repo.recordRestart(event.songId)
                StatsEvent.RecordShuffleSession -> repo.recordShuffleSession()
            }
        }
    }

    companion object { const val EXTRA_PLAYLIST_ID = "sonora.playlistId" }
}
