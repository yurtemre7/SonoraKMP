package de.yurtemre.sonora.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import de.yurtemre.sonora.playback.api.SleepTimerUiState
import de.yurtemre.sonora.shared.model.QueueState
import kotlinx.serialization.json.Json

/** Custom session commands (PLAY-02..16, 21, 26) and the session-extras codec shared by service and controller. */
object SessionCommands {
    private const val P = "de.yurtemre.sonora.cmd."
    const val SET_QUEUE = P + "SET_QUEUE"
    const val RESTORE_QUEUE = P + "RESTORE_QUEUE"
    const val PLAY_NEXT = P + "PLAY_NEXT"
    const val ADD_TO_QUEUE = P + "ADD_TO_QUEUE"
    const val REMOVE = P + "REMOVE"
    const val MOVE = P + "MOVE"
    const val SKIP_TO = P + "SKIP_TO"
    const val SET_SHUFFLE = P + "SET_SHUFFLE"
    const val TOGGLE_SHUFFLE = P + "TOGGLE_SHUFFLE"
    const val CYCLE_REPEAT = P + "CYCLE_REPEAT"
    const val SKIP_NEXT = P + "SKIP_NEXT"
    const val SKIP_PREVIOUS = P + "SKIP_PREVIOUS"
    const val SET_VOLUME = P + "SET_VOLUME"
    const val SET_SPEED = P + "SET_SPEED"
    const val SET_MFX_PRESET = P + "SET_MFX_PRESET"
    const val SET_BASS_BOOST = P + "SET_BASS_BOOST"
    const val SET_WARMTH = P + "SET_WARMTH"
    const val SET_LOFI = P + "SET_LOFI"
    const val SET_CUSTOM_EQ = P + "SET_CUSTOM_EQ"
    const val SET_EQ_BAND = P + "SET_EQ_BAND"
    const val APPLY_EQ_PRESET = P + "APPLY_EQ_PRESET"
    const val RESET_EFFECTS = P + "RESET_EFFECTS"
    const val SLEEP_START = P + "SLEEP_START"
    const val SLEEP_EXTEND = P + "SLEEP_EXTEND"
    const val SLEEP_STOP = P + "SLEEP_STOP"
    const val CLOSE_PLAYER = P + "CLOSE_PLAYER"

    val ALL = listOf(
        SET_QUEUE, RESTORE_QUEUE, PLAY_NEXT, ADD_TO_QUEUE, REMOVE, MOVE, SKIP_TO, SET_SHUFFLE, TOGGLE_SHUFFLE,
        CYCLE_REPEAT, SKIP_NEXT, SKIP_PREVIOUS, SET_VOLUME, SET_SPEED, SET_MFX_PRESET, SET_BASS_BOOST, SET_WARMTH,
        SET_LOFI, SET_CUSTOM_EQ, SET_EQ_BAND, APPLY_EQ_PRESET, RESET_EFFECTS, SLEEP_START, SLEEP_EXTEND, SLEEP_STOP,
        CLOSE_PLAYER,
    )

    /** Commands any (external) controller may send: the notification buttons. */
    val PUBLIC = listOf(SLEEP_EXTEND, SLEEP_STOP, CLOSE_PLAYER)

    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)

    // Argument keys
    const val ARG_IDS = "ids"
    const val ARG_START_SONG = "startSong"
    const val ARG_PLAYLIST = "playlist"
    const val ARG_SHUFFLE = "shuffle"
    const val ARG_UID = "uid"
    const val ARG_FROM = "from"
    const val ARG_TO = "to"
    const val ARG_ENABLED = "enabled"
    const val ARG_VALUE = "value"
    const val ARG_NAME = "name"
    const val ARG_BAND = "band"
    const val ARG_SECONDS = "seconds"
    const val ARG_FINISH_SONG = "finishSong"
    const val ARG_FADE = "fade"
    const val ARG_QUEUE_JSON = "queueJson"
    const val ARG_POSITION = "position"

    // Session extras keys
    private const val X_QUEUE = "x.queue"
    private const val X_SLEEP_KIND = "x.sleep.kind"
    private const val X_SLEEP_REMAINING = "x.sleep.remaining"
    private const val X_SLEEP_TOTAL = "x.sleep.total"
    private const val X_SLEEP_FADE = "x.sleep.fade"
    private const val X_SLEEP_FADING = "x.sleep.fading"
    private const val X_EQ_SUPPORTED = "x.eq.supported"
    private const val X_EQ_CENTERS = "x.eq.centers"
    private const val X_EQ_MIN = "x.eq.min"
    private const val X_EQ_MAX = "x.eq.max"

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeQueue(s: QueueState): String = json.encodeToString(QueueState.serializer(), s)
    fun decodeQueue(s: String?): QueueState? =
        s?.let { runCatching { json.decodeFromString(QueueState.serializer(), it) }.getOrNull() }

    data class Extras(
        val queue: QueueState = QueueState(),
        val sleep: SleepTimerUiState = SleepTimerUiState.Inactive,
        val eqSupported: Boolean = false,
        val eq: AudioEffects.EqInfo? = null,
    )

    fun toBundle(e: Extras): Bundle = Bundle().apply {
        putString(X_QUEUE, encodeQueue(e.queue))
        when (val s = e.sleep) {
            SleepTimerUiState.Inactive -> putInt(X_SLEEP_KIND, 0)
            is SleepTimerUiState.Countdown -> {
                putInt(X_SLEEP_KIND, 1)
                putLong(X_SLEEP_REMAINING, s.remainingMs); putLong(X_SLEEP_TOTAL, s.totalMs)
                putInt(X_SLEEP_FADE, s.fadeSeconds); putBoolean(X_SLEEP_FADING, s.isFading)
            }
            is SleepTimerUiState.FinishSong -> {
                putInt(X_SLEEP_KIND, 2)
                putInt(X_SLEEP_FADE, s.fadeSeconds); putBoolean(X_SLEEP_FADING, s.isFading)
            }
        }
        putBoolean(X_EQ_SUPPORTED, e.eqSupported)
        e.eq?.let {
            putIntArray(X_EQ_CENTERS, it.centersHz.toIntArray()); putInt(X_EQ_MIN, it.minMb); putInt(X_EQ_MAX, it.maxMb)
        }
    }

    fun fromBundle(b: Bundle): Extras {
        val sleep = when (b.getInt(X_SLEEP_KIND, 0)) {
            1 -> SleepTimerUiState.Countdown(
                b.getLong(X_SLEEP_REMAINING), b.getLong(X_SLEEP_TOTAL), b.getInt(X_SLEEP_FADE), b.getBoolean(X_SLEEP_FADING),
            )
            2 -> SleepTimerUiState.FinishSong(b.getInt(X_SLEEP_FADE), b.getBoolean(X_SLEEP_FADING))
            else -> SleepTimerUiState.Inactive
        }
        val eq = b.getIntArray(X_EQ_CENTERS)?.let { AudioEffects.EqInfo(it.toList(), b.getInt(X_EQ_MIN), b.getInt(X_EQ_MAX)) }
        return Extras(decodeQueue(b.getString(X_QUEUE)) ?: QueueState(), sleep, b.getBoolean(X_EQ_SUPPORTED), eq)
    }
}
