package de.yurtemre.sonora.widget

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player

/**
 * Pushes the player's (title, artist, artPath, isPlaying) to the widget,
 * deduplicated so position ticks never redraw it (WID-06). Attach with
 * `player.addListener(updater)` in the playback service; call [clear] on close.
 *
 * Starts with no last value, so attaching to an empty player doesn't
 * overwrite the persisted widget until something actually changes.
 */
class WidgetUpdater(context: Context, private val player: Player) : Player.Listener {
    private val appContext = context.applicationContext
    private var last: WidgetState? = null

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_MEDIA_METADATA_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
            )
        ) {
            push(snapshot())
        }
    }

    /** Re-pushes the current state unconditionally (e.g. after service start). */
    fun refresh() {
        last = null
        push(snapshot())
    }

    /** Shows "nothing playing" (queue closed/cleared). */
    fun clear() = push(WidgetState.EMPTY)

    /** Keeps the last song but shows the play icon (service stopping). */
    fun markStopped() {
        val current = last ?: WidgetStateStore.load(appContext)
        push(current.copy(playing = false))
    }

    private fun snapshot(): WidgetState {
        val item = player.currentMediaItem ?: return WidgetState.EMPTY
        val meta: MediaMetadata = player.mediaMetadata.takeIf { it != MediaMetadata.EMPTY } ?: item.mediaMetadata
        return WidgetState(
            hasTrack = true,
            title = meta.title?.toString(),
            artist = meta.artist?.toString(),
            artPath = meta.artworkUri.filePath(),
            playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
        )
    }

    private fun push(state: WidgetState) {
        if (state == last) return
        last = state
        WidgetStateStore.save(appContext, state)
        WidgetRenderer.renderAsync(appContext)
    }

    private fun Uri?.filePath(): String? = this?.takeIf { it.scheme == "file" || it.scheme == null }?.path
}
