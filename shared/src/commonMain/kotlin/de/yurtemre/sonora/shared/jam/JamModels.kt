package de.yurtemre.sonora.shared.jam

import kotlinx.serialization.Serializable

/** Jam wire protocol version; versioned separately from device sync. */
const val JAM_PROTOCOL_VERSION = 1

@Serializable
data class JamSong(val title: String, val artist: String, val album: String, val durationMs: Long)

@Serializable
data class JamState(
    val queue: List<JamSong>,
    val index: Int,
    val playing: Boolean,
    val positionMs: Long,
    val hostTimeMs: Long,
    val guestsCanAdd: Boolean,
    val guestsCanControl: Boolean,
)

@Serializable
enum class JamAction { Play, Pause, Next, Previous }

@Serializable
data class JamHelloPayload(val protocol: Int, val name: String)

sealed interface JamFrame
data class JamHello(val protocol: Int, val name: String) : JamFrame
data class JamStateFrame(val state: JamState) : JamFrame
data class JamAddRequest(val song: JamSong) : JamFrame
data class JamControl(val action: JamAction) : JamFrame
data class Ping(val id: Long, val sentMs: Long) : JamFrame
data class Pong(val id: Long, val sentMs: Long, val hostMs: Long) : JamFrame
data class JamEnd(val reason: String) : JamFrame
