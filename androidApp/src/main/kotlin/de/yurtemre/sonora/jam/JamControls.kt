package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.shared.model.Song
import kotlinx.coroutines.flow.StateFlow

/** What the Jam screens use of [JamController] (a seam, so the view model is tested with a fake). */
interface JamControls {
    val state: StateFlow<JamUiState>

    /** @throws java.io.IOException when hosting cannot start ([state] is then [JamUiState.HostFailed]). */
    suspend fun host(settings: JamSettings = JamSettings()): HostSession
    suspend fun join(host: String, port: Int, code: String, hostName: String)
    fun leave()
    fun dismiss()

    fun endJam()
    fun setGuestsCanAdd(allowed: Boolean)
    fun setGuestsCanControl(allowed: Boolean)
    fun kick(guestId: String)
    fun newCode()

    fun requestPlay()
    fun requestPause()
    fun requestNext()
    fun requestPrevious()
    /** Sends [songs] to the host (in batches, up to the Jam queue cap); returns how many were sent. */
    fun requestAdd(songs: List<Song>): Int
}
