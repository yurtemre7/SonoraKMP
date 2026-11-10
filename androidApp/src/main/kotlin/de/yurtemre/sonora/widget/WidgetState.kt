package de.yurtemre.sonora.widget

import android.content.Context

/** Last widget content, persisted so it survives process death (WID-03). */
data class WidgetState(
    val hasTrack: Boolean,
    val title: String?,
    val artist: String?,
    val artPath: String?,
    val playing: Boolean,
) {
    companion object {
        val EMPTY = WidgetState(false, null, null, null, false)
    }
}

object WidgetStateStore {
    private const val PREFS = "sonora_home_widget"
    private const val KEY_HAS_TRACK = "has_track"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_ART_PATH = "art_path"
    private const val KEY_PLAYING = "playing"

    fun save(context: Context, state: WidgetState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HAS_TRACK, state.hasTrack)
            .putString(KEY_TITLE, state.title)
            .putString(KEY_ARTIST, state.artist)
            .putString(KEY_ART_PATH, state.artPath)
            .putBoolean(KEY_PLAYING, state.playing)
            .apply()
    }

    fun load(context: Context): WidgetState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WidgetState(
            hasTrack = prefs.getBoolean(KEY_HAS_TRACK, false),
            title = prefs.getString(KEY_TITLE, null),
            artist = prefs.getString(KEY_ARTIST, null),
            artPath = prefs.getString(KEY_ART_PATH, null),
            playing = prefs.getBoolean(KEY_PLAYING, false),
        )
    }
}
