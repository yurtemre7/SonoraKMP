package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A guest's transport, mapped to requests to the host. Every call checks the host's current permission, so a
 * stale button can never act on the guest's own (mirrored) player.
 */
class JamGuestActions(private val jam: JamControls) {
    fun playPause(state: JamUiState.Joined) {
        if (!state.canControl) return
        if (state.playing) jam.requestPause() else jam.requestPlay()
    }

    fun play(state: JamUiState.Joined) {
        if (state.canControl) jam.requestPlay()
    }

    fun pause(state: JamUiState.Joined) {
        if (state.canControl) jam.requestPause()
    }

    fun next(state: JamUiState.Joined) {
        if (state.canControl) jam.requestNext()
    }

    fun previous(state: JamUiState.Joined) {
        if (state.canControl) jam.requestPrevious()
    }

    /** Asks the host to add [song]; false when guests may not add songs. */
    fun add(state: JamUiState.Joined, song: Song): Boolean {
        if (!state.canAdd) return false
        jam.requestAdd(song)
        return true
    }
}

/** A "play my own music" call held while this phone is a Jam guest. [isAdd]: play next / add to queue. */
class OwnPlayback(val songs: List<Song>, val isAdd: Boolean, internal val run: () -> Unit)

/**
 * The one place that keeps a Jam guest's own playback from fighting the mirrored queue. While the controller is
 * [JamUiState.Joined], a "play my own music" call ([request]) does not run: it becomes [pending], which the app
 * shows as "Leave the Jam to play your own music?". [leaveAndPlay] leaves the Jam (the own queue comes back) and
 * then runs it once; [stay] drops it; for an add the host allows, [addToJam] sends the songs to the host instead.
 * Hosts and phones outside a Jam are never affected.
 */
class JamPlaybackGuard(private val jam: JamControls) {
    private val _pending = MutableStateFlow<OwnPlayback?>(null)
    val pending: StateFlow<OwnPlayback?> = _pending.asStateFlow()

    val guestActions = JamGuestActions(jam)

    /** The Jam state while this phone is a guest (mirroring the host), else null. */
    fun guest(): JamUiState.Joined? = jam.state.value as? JamUiState.Joined

    /** Runs [run] now (true) unless this phone is a Jam guest; then it waits as [pending] (false). */
    fun request(songs: List<Song>, isAdd: Boolean, run: () -> Unit): Boolean {
        if (guest() == null) {
            run()
            return true
        }
        _pending.value = OwnPlayback(songs, isAdd, run)
        return false
    }

    fun canAddToJam(p: OwnPlayback): Boolean = p.isAdd && guest()?.canAdd == true

    fun leaveAndPlay() {
        val p = _pending.value ?: return
        _pending.value = null
        jam.leave()
        p.run()
    }

    fun addToJam() {
        val p = _pending.value ?: return
        val state = guest() ?: return
        if (!canAddToJam(p)) return
        _pending.value = null
        p.songs.forEach { guestActions.add(state, it) }
    }

    fun stay() {
        _pending.value = null
    }
}
