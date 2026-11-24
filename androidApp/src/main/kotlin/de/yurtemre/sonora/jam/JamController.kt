package de.yurtemre.sonora.jam

import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.jam.JamAction
import de.yurtemre.sonora.shared.jam.JamError
import de.yurtemre.sonora.shared.jam.JamException
import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.shared.model.Song
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Jam needs from the app, as plain functions so the controller is tested with fakes and a virtual clock.
 * [nowMs] must be the clock the Jam client and server stamp with (monotonic, e.g. `elapsedRealtime`).
 */
class JamEnvironment(
    val hostTransport: () -> JamHostTransport,
    val connector: JamGuestConnector,
    /** The local library right now (songs are matched against it). */
    val library: () -> List<Song>,
    val nowMs: () -> Long,
    /** The per-device speaker delay setting (clamped again where it is used). */
    val speakerDelayMs: () -> Int = { 0 },
    /** Records a skip of a song in this phone's stats (the guest's own Next). */
    val recordSkip: suspend (Long) -> Unit = {},
    val deviceName: () -> String = { "" },
    /** Advertises a hosted Jam on the LAN (mDNS); closing stops it. May throw: manual IP:port still works. */
    val advertise: (port: Int, hostName: String) -> AutoCloseable = { _, _ -> AutoCloseable {} },
    /** Keeps the process alive while a Jam runs (the foreground [JamService]). */
    val startService: () -> Unit = {},
    /** A fresh 6-digit code. */
    val newCode: () -> String,
    /** Debug builds only: start-gap measurements for the real-phone checks. */
    val debugLog: ((String) -> Unit)? = null,
)

/** A hosted Jam: guests join at [port] with [code]. */
data class HostSession(val code: String, val port: Int)

/**
 * Process-wide Jam state machine: hosting ([host]) or joining ([join]) a Jam over the existing player. The UI
 * observes [state]; a host controls permissions, guests and the code; a guest sends requests (only when the host
 * allows). [leave] ends whichever role is active: a host's Jam ends for everyone, a guest's own queue comes back.
 * All calls on [scope]'s thread (main).
 */
class JamController(
    private val scope: CoroutineScope,
    private val playback: PlaybackConnection,
    private val env: JamEnvironment,
) {
    private val _state = MutableStateFlow<JamUiState>(JamUiState.Idle)
    val state: StateFlow<JamUiState> = _state.asStateFlow()

    private var hostSession: JamHostSession? = null
    private var guestSession: JamGuestSession? = null
    /** Bumped by [leave]: a [host] or [join] still suspended from before may not come back as a session. */
    private var generation = 0

    /**
     * Starts hosting with [settings]: a fresh code, a server on a free port, advertised on the LAN.
     * @throws IOException when the server cannot start ([state] is then [JamUiState.HostFailed]).
     */
    suspend fun host(settings: JamSettings = JamSettings()): HostSession {
        leave()
        val gen = generation
        val transport = env.hostTransport()
        val code = env.newCode()
        val port = try {
            transport.start(code)
        } catch (e: CancellationException) {
            transport.stop()
            throw e
        } catch (e: Exception) {
            transport.stop()
            if (gen == generation) _state.value = JamUiState.HostFailed
            throw IOException("Jam server did not start", e)
        }
        if (gen != generation) { // left (or joined) while the server started
            transport.stop()
            throw CancellationException("The Jam was left while it started")
        }
        lateinit var session: JamHostSession
        session = JamHostSession(scope, playback, env, transport, code, port, settings) { ui ->
            if (hostSession === session) _state.value = ui
        }
        hostSession = session
        session.start()
        env.startService()
        return HostSession(code, port)
    }

    /** Joins the Jam at [host]:[port] with [code]; a failure leaves [JamUiState.Error] (typed) and the player as it was. */
    suspend fun join(host: String, port: Int, code: String, hostName: String) {
        leave()
        val gen = generation
        _state.value = JamUiState.Joining
        val link = try {
            env.connector.join(host, port, code, env.deviceName())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen == generation) _state.value = JamUiState.Error((e as? JamException)?.error ?: JamError.HostGone)
            return
        }
        if (gen != generation) { // left (or started hosting) while connecting
            link.close()
            return
        }
        lateinit var session: JamGuestSession
        session = JamGuestSession(
            scope, playback, env, JamRejoin(host, port, hostName), env.deviceName(), link,
            publish = { ui -> if (guestSession === session) _state.value = ui },
            onLocalClose = { if (guestSession === session) leave() },
        )
        guestSession = session
        session.start()
        env.startService()
    }

    /** Ends hosting (for everyone) or leaves as a guest (own queue back). Idempotent. */
    fun leave() {
        generation++
        hostSession?.let {
            hostSession = null
            it.end()
        }
        guestSession?.let {
            guestSession = null
            it.leave()
        }
        _state.value = JamUiState.Idle
    }

    /** Back to [JamUiState.Idle] after [JamUiState.Ended], [JamUiState.Error] or [JamUiState.HostFailed]. */
    fun dismiss() {
        val s = _state.value
        if (s is JamUiState.Ended || s is JamUiState.Error || s is JamUiState.HostFailed) leave()
    }

    // ---- host -------------------------------------------------------------------------------------------------------

    fun endJam() = leave()

    fun setGuestsCanAdd(allowed: Boolean) {
        hostSession?.let { it.setSettings(it.settings.copy(guestsCanAdd = allowed)) }
    }

    fun setGuestsCanControl(allowed: Boolean) {
        hostSession?.let { it.setSettings(it.settings.copy(guestsCanControl = allowed)) }
    }

    fun kick(guestId: String) {
        hostSession?.kick(guestId)
    }

    /** A new code after [JamUiState.Hosting.codeExhausted]; guests in the Jam stay. */
    fun newCode() {
        hostSession?.newCode()
    }

    // ---- guest ------------------------------------------------------------------------------------------------------

    fun requestPlay() = guestSession?.request(JamAction.Play)
    fun requestPause() = guestSession?.request(JamAction.Pause)
    fun requestNext() = guestSession?.request(JamAction.Next)
    fun requestPrevious() = guestSession?.request(JamAction.Previous)
    fun requestAdd(song: Song) = guestSession?.requestAdd(song)

    /** Wi-Fi was lost or changed ([JamService]): a guest ends its connection cleanly and may rejoin. */
    fun onNetworkLost() {
        guestSession?.onNetworkLost()
    }

    companion object {
        /** Host player changes closer together than this are one action (one frame). */
        const val HOST_DEBOUNCE_MS = 150L
    }
}
