package de.yurtemre.sonora.ui.screens.jam

import de.yurtemre.sonora.shared.devicesync.DeviceSyncServer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.yurtemre.sonora.jam.DiscoveredJam
import de.yurtemre.sonora.jam.JamControls
import de.yurtemre.sonora.jam.JamGuestUi
import de.yurtemre.sonora.jam.JamRejoin
import de.yurtemre.sonora.jam.JamUiState
import de.yurtemre.sonora.shared.jam.JAM_PROTOCOL_VERSION
import de.yurtemre.sonora.shared.jam.JamError
import de.yurtemre.sonora.shared.jam.JamSettings
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which Jam screen shows. */
enum class JamScreen { Start, Join, Host, Guest, Ended, HostFailed }

/** A found Jam; [compatible] is false when its host runs another Jam protocol version (it cannot be joined). */
data class JamListItem(val jam: DiscoveredJam, val compatible: Boolean)

/** A question the host (or guest) must confirm first. */
sealed interface JamConfirm {
    data class Kick(val guest: JamGuestUi) : JamConfirm
    data object End : JamConfirm
}

/** Everything the join screen shows. */
data class JamJoinUi(
    val jams: List<JamListItem> = emptyList(),
    /** The Jam joined with the code: the one picked, else the only compatible one found. */
    val selected: String? = null,
    val discoveryFailed: Boolean = false,
    val searchTimedOut: Boolean = false,
    val manualOpen: Boolean = false,
    val manualText: String = "",
    val addressInvalid: Boolean = false,
    /** Several Jams were found and none was picked. */
    val needJam: Boolean = false,
    /** Set after a Wi-Fi change: the code joins this host again. */
    val rejoinName: String? = null,
    val code: String = "",
    val joining: Boolean = false,
    val joiningName: String? = null,
    val error: JamError? = null,
    /** Seconds until joining is allowed again after [JamError.Locked]; 0 when not locked. */
    val lockSecondsLeft: Int = 0,
) {
    val canSubmit: Boolean get() = !joining && lockSecondsLeft == 0 && code.length == CODE_DIGITS
}

private const val CODE_DIGITS = 6

private data class JoinForm(
    val selectedName: String? = null,
    val manualToggled: Boolean? = null,
    val manualText: String = "",
    val addressInvalid: Boolean = false,
    val needJam: Boolean = false,
    val rejoin: JamRejoin? = null,
    val code: String = "",
    val joiningName: String? = null,
)

private data class Discovery(val jams: List<DiscoveredJam> = emptyList(), val failed: Boolean = false, val timedOut: Boolean = false)

/**
 * The Jam screens' state over the process-wide [JamControls]: which screen shows, the join form (discovered Jams,
 * manual address, code, lockout countdown) and the host's confirmations. [discover] runs while the join screen is
 * visible ([startDiscovery] / [stopDiscovery]); [nowMs] drives the lockout countdown.
 */
class JamViewModel(
    private val jam: JamControls,
    private val discover: () -> Flow<List<DiscoveredJam>>,
    private val nowMs: () -> Long,
) : ViewModel() {
    val state: StateFlow<JamUiState> = jam.state

    private val joinOpen = MutableStateFlow(false)
    // The join form's inputs; every change recomputes [join] at once (no dispatch), so a 6th digit joins with
    // exactly what is on screen.
    private var form = JoinForm()
        set(value) { field = value; refreshJoin() }
    private var discovery = Discovery()
        set(value) { field = value; refreshJoin() }
    private var lockedUntil = 0L
    private val _join = MutableStateFlow(JamJoinUi())
    val join: StateFlow<JamJoinUi> = _join.asStateFlow()
    private val _confirm = MutableStateFlow<JamConfirm?>(null)
    val confirm: StateFlow<JamConfirm?> = _confirm.asStateFlow()

    private var discoveryJob: Job? = null
    private val _starting = MutableStateFlow(false)
    /** A [startHosting] is under way (the server is starting). */
    val starting: StateFlow<Boolean> = _starting.asStateFlow()
    private var lastSettings = JamSettings()

    val screen: StateFlow<JamScreen> = combine(jam.state, joinOpen) { s, open ->
        when (s) {
            is JamUiState.Hosting -> JamScreen.Host
            is JamUiState.Joined -> JamScreen.Guest
            JamUiState.Joining, is JamUiState.Error -> JamScreen.Join
            is JamUiState.Ended -> JamScreen.Ended
            JamUiState.HostFailed -> JamScreen.HostFailed
            JamUiState.Idle -> if (open) JamScreen.Join else JamScreen.Start
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, JamScreen.Start)

    private fun refreshJoin() {
        val s = jam.state.value
        val f = form
        val d = discovery
        val items = d.jams.map { JamListItem(it, it.protocol == null || it.protocol == JAM_PROTOCOL_VERSION) }
        _join.value = JamJoinUi(
            jams = items,
            selected = effectiveJam(items, f.selectedName)?.serviceName,
            discoveryFailed = d.failed,
            searchTimedOut = d.timedOut,
            manualOpen = f.manualText.isNotBlank() || (f.manualToggled ?: (d.failed || (d.timedOut && items.isEmpty()))),
            manualText = f.manualText,
            addressInvalid = f.addressInvalid,
            needJam = f.needJam,
            rejoinName = f.rejoin?.hostName,
            code = f.code,
            joining = s == JamUiState.Joining,
            joiningName = f.joiningName,
            error = (s as? JamUiState.Error)?.error,
            lockSecondsLeft = ((lockedUntil - nowMs() + 999) / 1000).coerceAtLeast(0).toInt(),
        )
    }

    init {
        // An error left from an earlier screen belongs to an attempt that is over (its lockout may have ended long
        // ago): never restart a countdown for it.
        if (jam.state.value is JamUiState.Error) jam.dismiss()
        viewModelScope.launch {
            jam.state.collect { s ->
                if (s !is JamUiState.Hosting) _confirm.value = null
                else (_confirm.value as? JamConfirm.Kick)?.let { k -> if (s.guests.none { it.id == k.guest.id }) _confirm.value = null }
                val error = (s as? JamUiState.Error)?.error
                if (error == JamError.Locked) lockedUntil = nowMs() + LOCKOUT_MS
                // Every refused attempt clears the code: the next one is typed fresh (and the 6th digit joins).
                if (error != null) form = form.copy(code = "") else refreshJoin()
                if (error == JamError.Locked) countDownLock()
            }
        }
    }

    // ---- join -------------------------------------------------------------------------------------------------------

    fun openJoin() { joinOpen.value = true }

    /** Back from the join form to the start screen (an error or a rejoin target is dropped). */
    fun closeJoin() {
        stopDiscovery()
        joinOpen.value = false
        form = JoinForm()
        if (jam.state.value is JamUiState.Error) jam.dismiss()
    }

    /** Stops a join that is still connecting; the form stays. */
    fun cancelJoin() {
        if (jam.state.value == JamUiState.Joining) jam.leave()
    }

    /** Starts (or restarts) LAN discovery and the search timer that opens the manual address field. */
    fun startDiscovery() {
        discoveryJob?.cancel()
        discovery = Discovery()
        discoveryJob = viewModelScope.launch {
            launch {
                delay(SEARCH_TIMEOUT_MS)
                discovery = discovery.copy(timedOut = true)
            }
            try {
                discover().collect { list -> discovery = discovery.copy(jams = list) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                discovery = discovery.copy(failed = true)
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    fun select(serviceName: String) {
        val item = join.value.jams.firstOrNull { it.jam.serviceName == serviceName } ?: return
        if (!item.compatible) return
        form = form.copy(selectedName = serviceName, manualText = "", manualToggled = false, needJam = false, rejoin = null)
    }

    fun setManualText(text: String) {
        form = form.copy(manualText = text.trim(), addressInvalid = false, rejoin = null)
    }

    fun toggleManual() {
        val open = join.value.manualOpen
        // Collapsing is an explicit choice to stop using the typed address.
        form = form.copy(manualToggled = !open, manualText = if (open) "" else form.manualText, addressInvalid = false)
    }

    /** Keeps digits only (at most 6); the 6th digit joins. */
    fun setCode(raw: String) {
        val digits = raw.filter { it in '0'..'9' }.take(CODE_DIGITS)
        val completed = digits.length == CODE_DIGITS && form.code.length != CODE_DIGITS
        form = form.copy(code = digits, needJam = false)
        if (completed) submit()
    }

    fun submit() {
        val ui = join.value
        if (!ui.canSubmit) return
        val f = form
        val target: Pair<JamAddress, String> = when {
            f.rejoin != null -> JamAddress(f.rejoin.host, f.rejoin.port) to f.rejoin.hostName
            ui.manualOpen && f.manualText.isNotBlank() -> {
                val address = parseJamAddress(f.manualText)
                if (address == null) {
                    form = form.copy(addressInvalid = true)
                    return
                }
                address to address.host
            }
            else -> {
                val found = ui.jams.firstOrNull { it.jam.serviceName == ui.selected }?.jam
                if (found == null) {
                    form = form.copy(needJam = true)
                    return
                }
                JamAddress(found.host, found.port) to found.hostName
            }
        }
        val (address, name) = target
        form = form.copy(joiningName = name, addressInvalid = false, needJam = false)
        viewModelScope.launch { jam.join(address.host, address.port, f.code, name) }
    }

    /** After a Wi-Fi change: back to the join screen, aimed at the same host; the code is needed again. */
    fun rejoin() {
        val target = (jam.state.value as? JamUiState.Ended)?.rejoin ?: return
        jam.dismiss()
        form = JoinForm(rejoin = target)
        joinOpen.value = true
    }

    private fun countDownLock() {
        viewModelScope.launch {
            while (nowMs() < lockedUntil) {
                delay(1_000)
                refreshJoin()
            }
            // Over: back to the plain join form (the error belonged to the attempt that was refused).
            if ((jam.state.value as? JamUiState.Error)?.error == JamError.Locked) jam.dismiss()
        }
    }

    // ---- host -------------------------------------------------------------------------------------------------------

    /** Starts hosting once per screen (a recreated screen does not start a second Jam); failure shows HostFailed. */
    fun startHosting(settings: JamSettings) {
        if (_starting.value || jam.state.value is JamUiState.Hosting) return
        _starting.value = true
        lastSettings = settings
        viewModelScope.launch {
            try {
                jam.host(settings)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // The state is HostFailed; the screen offers to try again.
            } finally {
                _starting.value = false
            }
        }
    }

    /** After [JamUiState.HostFailed]: the same permissions again. */
    fun retryHosting() {
        jam.dismiss()
        startHosting(lastSettings)
    }

    fun setGuestsCanAdd(allowed: Boolean) = jam.setGuestsCanAdd(allowed)
    fun setGuestsCanControl(allowed: Boolean) = jam.setGuestsCanControl(allowed)
    fun newCode() = jam.newCode()

    fun requestKick(guest: JamGuestUi) { _confirm.value = JamConfirm.Kick(guest) }
    fun requestEnd() { _confirm.value = JamConfirm.End }
    fun dismissConfirm() { _confirm.value = null }

    fun confirm() {
        when (val c = _confirm.value) {
            is JamConfirm.Kick -> jam.kick(c.guest.id)
            JamConfirm.End -> jam.endJam()
            null -> Unit
        }
        _confirm.value = null
    }

    // ---- both -------------------------------------------------------------------------------------------------------

    /** Leaves (guest) or ends without asking (used after a confirmation); back to the start screen. */
    fun leave() {
        joinOpen.value = false
        jam.leave()
    }

    /** Clears an end state ([JamUiState.Ended], [JamUiState.Error], [JamUiState.HostFailed]). */
    fun dismiss() {
        joinOpen.value = false
        jam.dismiss()
    }

    override fun onCleared() {
        stopDiscovery()
    }

    companion object {
        /** An empty list opens the manual address field after this long. */
        const val SEARCH_TIMEOUT_MS = 10_000L
        /** The host's cool-down after too many wrong codes (the device sync one, reused by the Jam's join gate). */
        const val LOCKOUT_MS = DeviceSyncServer.COOL_DOWN_MS

        /** The Jam picked while it is still listed (and compatible), else the only compatible one found. */
        internal fun effectiveJam(items: List<JamListItem>, selectedName: String?): DiscoveredJam? {
            val ok = items.filter { it.compatible }.map { it.jam }
            return ok.firstOrNull { it.serviceName == selectedName } ?: ok.singleOrNull()
        }
    }
}
