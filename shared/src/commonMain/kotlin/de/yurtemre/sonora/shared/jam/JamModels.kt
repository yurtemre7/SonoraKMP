package de.yurtemre.sonora.shared.jam

import kotlinx.serialization.Serializable

/** Jam wire protocol version; versioned separately from device sync. */
const val JAM_PROTOCOL_VERSION = 1

/** Guests can add songs until the Jam queue holds this many; the host takes the first ones that fit. */
const val MAX_JAM_QUEUE = 500

/** Songs per [JamAddManyRequest] frame; larger adds go out as several frames ([jamAddRequests]). */
const val MAX_ADD_BATCH = 100

@Serializable
data class JamSong(val title: String, val artist: String, val album: String, val durationMs: Long)

/** The host's repeat mode as guests mirror it ([JamState.repeat]). */
@Serializable
enum class JamRepeat { Off, All, One }

/**
 * [repeat] is the host's repeat mode; frames without it (older builds) decode as [JamRepeat.Off]. With
 * [JamRepeat.One] a guest loops the current song in its own player (never a reload at each loop).
 */
@Serializable
data class JamState(
    val queue: List<JamSong>,
    val index: Int,
    val playing: Boolean,
    val positionMs: Long,
    val hostTimeMs: Long,
    val guestsCanAdd: Boolean,
    val guestsCanControl: Boolean,
    val repeat: JamRepeat = JamRepeat.Off,
)

@Serializable
enum class JamAction { Play, Pause, Next, Previous }

@Serializable
internal data class JamHelloPayload(val protocol: Int, val name: String)

sealed interface JamFrame
data class JamHello(val protocol: Int, val name: String) : JamFrame
data class JamStateFrame(val state: JamState) : JamFrame
data class JamAddRequest(val song: JamSong) : JamFrame

/** Guest → host: several songs at once (at most [MAX_ADD_BATCH]); one request against the host's rate limit. */
data class JamAddManyRequest(val songs: List<JamSong>) : JamFrame
data class JamControl(val action: JamAction) : JamFrame
data class Ping(val id: Long, val sentMs: Long) : JamFrame
data class Pong(val id: Long, val sentMs: Long, val hostMs: Long) : JamFrame
data class JamEnd(val reason: String) : JamFrame

/**
 * Host → guest, right after a successful join or reconnect, inside the encrypted channel: the guest's id and its
 * single-use reconnect key (128 bits, lowercase hex). Consumed by the Jam client, never shown.
 */
data class JamWelcome(val guestId: String, val reconnectKey: String) : JamFrame {
    override fun toString() = "JamWelcome(guestId=$guestId)"
}

/** [songs] as add frames of at most [MAX_ADD_BATCH] songs each, in order. */
fun jamAddRequests(songs: List<JamSong>): List<JamAddManyRequest> = songs.chunked(MAX_ADD_BATCH).map(::JamAddManyRequest)
