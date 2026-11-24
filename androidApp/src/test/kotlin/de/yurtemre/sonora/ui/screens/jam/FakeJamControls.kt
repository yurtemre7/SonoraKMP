package de.yurtemre.sonora.ui.screens.jam

import de.yurtemre.sonora.jam.HostSession
import de.yurtemre.sonora.jam.JamControls
import de.yurtemre.sonora.jam.JamUiState
import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.shared.model.Song
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow

/** Records every call; the test drives [state] like the real controller would. */
class FakeJamControls : JamControls {
    override val state = MutableStateFlow<JamUiState>(JamUiState.Idle)
    val calls = mutableListOf<String>()
    val joins = mutableListOf<List<Any>>()
    val hosts = mutableListOf<JamSettings>()
    val added = mutableListOf<Song>()
    /** What [join] leaves behind (null: stays Joining). */
    var joinResult: JamUiState? = null
    var hostFails = false

    override suspend fun host(settings: JamSettings): HostSession {
        hosts += settings
        if (hostFails) {
            state.value = JamUiState.HostFailed
            throw IOException("no server")
        }
        state.value = JamUiState.Hosting("123456", 41_234, emptyList(), settings)
        return HostSession("123456", 41_234)
    }

    override suspend fun join(host: String, port: Int, code: String, hostName: String) {
        joins += listOf(host, port, code, hostName)
        state.value = JamUiState.Joining
        joinResult?.let { state.value = it }
    }

    override fun leave() { calls += "leave"; state.value = JamUiState.Idle }
    override fun dismiss() { calls += "dismiss"; state.value = JamUiState.Idle }
    override fun endJam() { calls += "endJam"; state.value = JamUiState.Idle }
    override fun setGuestsCanAdd(allowed: Boolean) { calls += "canAdd=$allowed" }
    override fun setGuestsCanControl(allowed: Boolean) { calls += "canControl=$allowed" }
    override fun kick(guestId: String) { calls += "kick=$guestId" }
    override fun newCode() { calls += "newCode" }
    override fun requestPlay() { calls += "play" }
    override fun requestPause() { calls += "pause" }
    override fun requestNext() { calls += "next" }
    override fun requestPrevious() { calls += "previous" }
    override fun requestAdd(song: Song) { added += song }
}
