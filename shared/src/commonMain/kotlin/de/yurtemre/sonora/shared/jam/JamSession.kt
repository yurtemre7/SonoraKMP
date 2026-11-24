package de.yurtemre.sonora.shared.jam

import de.yurtemre.sonora.shared.model.Song
import kotlin.math.abs

/** Maximum number of simultaneous guests in a Jam. */
const val MAX_GUESTS = 7

/** Guest/host drift above this many milliseconds is flagged (exactly this value is not drift). */
const val JAM_DRIFT_THRESHOLD_MS = 2000L

/** Why joining (or staying in) a Jam failed; typed so the UI (Task 12) picks its own text. */
enum class JamError {
    /** The Jam already has [MAX_GUESTS] guests (reserved reconnect slots included). */
    Full,

    /** Host and guest speak different [JAM_PROTOCOL_VERSION]s. */
    VersionMismatch,

    /** The J-PAKE round failed: the guest typed another code. */
    WrongCode,

    /** Too many wrong codes: the host refuses new joins for a short cool-down. */
    Locked,

    /** Too many wrong codes in total: no more joins until the host shows a new code. */
    CodeExhausted,

    /** The host could not be reached or the connection broke without a [JamEnd]. */
    HostGone,

    /** The reconnect key is unknown, already used, revoked (kick/stop) or older than the reconnect window. */
    ReconnectRefused,
}

class JamException(val error: JamError) : Exception(error.name)

data class JamSettings(val guestsCanAdd: Boolean = true, val guestsCanControl: Boolean = false)

data class GuestInfo(val name: String)

data class JamHostState(
    val guests: Map<String, GuestInfo>,
    val state: JamState,
    val settings: JamSettings,
) {
    /** Null when [id] may join (a known id may always reconnect); [JamError.Full] otherwise. */
    fun canJoin(id: String): JamError? = if (id !in guests && guests.size >= MAX_GUESTS) JamError.Full else null

    companion object {
        fun initial(settings: JamSettings = JamSettings()) = JamHostState(
            guests = emptyMap(),
            state = JamState(emptyList(), 0, false, 0, 0, settings.guestsCanAdd, settings.guestsCanControl),
            settings = settings,
        )
    }
}

/** Host operations; every time-dependent one carries the host timestamp from the caller (the reducer reads no clock). */
sealed interface HostAction {
    data class SetQueue(val queue: List<JamSong>, val index: Int) : HostAction
    data class Play(val positionMs: Long, val hostTimeMs: Long) : HostAction
    data class Pause(val positionMs: Long, val hostTimeMs: Long) : HostAction
    data class Next(val hostTimeMs: Long) : HostAction
    data class Previous(val hostTimeMs: Long) : HostAction
    data class SetSettings(val settings: JamSettings) : HostAction
    data class AddSong(val song: JamSong) : HostAction
}

sealed interface JamEvent {
    data class GuestJoined(val id: String, val name: String) : JamEvent
    data class GuestLeft(val id: String) : JamEvent
    data class AddRequest(val id: String, val song: JamSong) : JamEvent
    data class Control(val id: String, val action: JamAction, val hostTimeMs: Long) : JamEvent
    data class HostAction(val action: de.yurtemre.sonora.shared.jam.HostAction) : JamEvent
}

private fun JamState.positionAt(hostTimeMs: Long): Long =
    if (playing) positionMs + (hostTimeMs - this.hostTimeMs).coerceAtLeast(0) else positionMs

private fun JamState.step(delta: Int, hostTimeMs: Long): JamState {
    val target = index + delta
    if (target !in queue.indices) return this
    return copy(index = target, positionMs = 0, hostTimeMs = hostTimeMs)
}

private fun JamState.perform(action: JamAction, hostTimeMs: Long): JamState = when (action) {
    JamAction.Play -> if (playing) this else copy(playing = true, hostTimeMs = hostTimeMs)
    JamAction.Pause -> copy(playing = false, positionMs = positionAt(hostTimeMs), hostTimeMs = hostTimeMs)
    JamAction.Next -> step(1, hostTimeMs)
    JamAction.Previous -> step(-1, hostTimeMs)
}

private fun JamHostState.withState(s: JamState) = copy(state = s)

/** Pure reducer. A refused join (see [JamHostState.canJoin]) leaves the state unchanged. */
fun JamHostState.apply(event: JamEvent): JamHostState = when (event) {
    is JamEvent.GuestJoined ->
        if (canJoin(event.id) != null) this else copy(guests = guests + (event.id to GuestInfo(event.name)))
    is JamEvent.GuestLeft -> copy(guests = guests - event.id)
    is JamEvent.AddRequest ->
        if (event.id !in guests || !settings.guestsCanAdd) this
        else withState(state.copy(queue = state.queue + event.song))
    is JamEvent.Control ->
        if (event.id !in guests || !settings.guestsCanControl) this
        else withState(state.perform(event.action, event.hostTimeMs))
    is JamEvent.HostAction -> when (val a = event.action) {
        is HostAction.SetQueue -> withState(
            state.copy(queue = a.queue, index = a.index.coerceIn(0, maxOf(a.queue.lastIndex, 0))),
        )
        is HostAction.Play -> withState(state.copy(playing = true, positionMs = a.positionMs, hostTimeMs = a.hostTimeMs))
        is HostAction.Pause -> withState(state.copy(playing = false, positionMs = a.positionMs, hostTimeMs = a.hostTimeMs))
        is HostAction.Next -> withState(state.step(1, a.hostTimeMs))
        is HostAction.Previous -> withState(state.step(-1, a.hostTimeMs))
        is HostAction.SetSettings -> copy(
            settings = a.settings,
            state = state.copy(guestsCanAdd = a.settings.guestsCanAdd, guestsCanControl = a.settings.guestsCanControl),
        )
        is HostAction.AddSong -> withState(state.copy(queue = state.queue + a.song))
    }
}

/**
 * What a guest should do with the current Jam state. Deliberately has no speed or pitch field: guests never
 * change playback rate to chase the host.
 */
sealed interface GuestPlan {
    /** The song at jam queue position [index] is not in the local library; the caller skips it silently. */
    data class Skip(val index: Int) : GuestPlan

    /**
     * Start [song] at [positionMs] when the local clock reaches [startAtLocalMs]. [seekOnNextBoundary] means the
     * guest has drifted more than [JAM_DRIFT_THRESHOLD_MS]; the player applies that correction only at the next
     * pause or song change, never mid-playback.
     */
    data class Play(
        val song: Song,
        val startAtLocalMs: Long,
        val positionMs: Long,
        val seekOnNextBoundary: Boolean,
    ) : GuestPlan
}

/**
 * [actualPositionMs] is the guest's current player position, or null when nothing is playing (never drift).
 * Expected position extrapolates the host position by host-clock time elapsed while playing.
 */
fun planPlayback(state: JamState, library: List<Song>, clock: ClockSync, nowMs: Long, actualPositionMs: Long?): GuestPlan {
    val jamSong = state.queue.getOrNull(state.index) ?: return GuestPlan.Skip(state.index)
    val song = JamSongMatcher.match(jamSong, library) ?: return GuestPlan.Skip(state.index)
    val hostNow = nowMs - clock.toLocal(0) // toLocal(h) = h - offset, so toLocal(0) = -offset
    val expected = state.positionAt(hostNow)
    val drift = actualPositionMs != null && abs(expected - actualPositionMs) > JAM_DRIFT_THRESHOLD_MS
    return GuestPlan.Play(song, clock.toLocal(state.hostTimeMs), expected, drift)
}

/** Next queue index at or after [from] whose song exists in [library], or null; lets a Jam continue past missing songs. */
fun nextPlayableIndex(state: JamState, library: List<Song>, from: Int): Int? =
    (from.coerceAtLeast(0) until state.queue.size).firstOrNull { JamSongMatcher.match(state.queue[it], library) != null }
