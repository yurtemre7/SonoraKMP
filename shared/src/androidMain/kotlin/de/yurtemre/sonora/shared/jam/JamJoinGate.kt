package de.yurtemre.sonora.shared.jam

import de.yurtemre.sonora.shared.devicesync.DeviceSyncAuthException
import de.yurtemre.sonora.shared.devicesync.JpakeHandshake
import de.yurtemre.sonora.shared.devicesync.PairingCode
import de.yurtemre.sonora.shared.devicesync.PairingLockout
import de.yurtemre.sonora.shared.devicesync.Role
import de.yurtemre.sonora.shared.devicesync.SecureChannel
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Who may try the Jam code, and how often ([JamServer]'s join admission):
 * - at most [maxInHandshake] joins between their preamble and the end of their J-PAKE round ([STATUS_BUSY]
 *   beyond that, so joins cannot take the handshake slots reconnects need);
 * - J-PAKE rounds one at a time, so every guess is counted before the next connection is checked against the
 *   lockout: 3 wrong codes, then the timed lock ([PairingLockout]);
 * - after [MAX_WRONG_CODES] wrong codes in total the code is dead ([exhausted]) until [replaceCode].
 */
internal class JamJoinGate(clock: () -> Long, private val maxInHandshake: Int) {
    private val lockout = PairingLockout(clock)
    private val lock = Any()
    private val turn = Mutex()
    private var code = ""
    /** Wrong codes against [code] in total (the timed lock resets its own count, this one only on [replaceCode]). */
    private var failures = 0
    private val _exhausted = MutableStateFlow(false)
    private val _inHandshake = MutableStateFlow(0)

    val lockedUntilMs: StateFlow<Long> = lockout.lockedUntilMs
    val exhausted: StateFlow<Boolean> = _exhausted.asStateFlow()
    val inHandshake: StateFlow<Int> = _inHandshake.asStateFlow()

    /** A finished J-PAKE round: the channel and the code it proved. */
    class Pairing(val channel: SecureChannel, val code: String)

    fun start(code: String) = synchronized(lock) {
        requireCode(code)
        this.code = code
    }

    /** New code: clears the wrong-code counts, the cool-down and [exhausted]. The same code again is refused. */
    fun replaceCode(newCode: String) = synchronized(lock) {
        requireCode(newCode)
        require(newCode != code) { "The new Jam code must differ from the current one" }
        code = newCode
        failures = 0
        _exhausted.value = false
        lockout.clear()
    }

    /** Whether [code] is still the Jam's code (a pairing with a replaced code must not register). */
    fun isCurrent(code: String) = synchronized(lock) { code == this.code }

    /**
     * Runs the join after its preamble: slot, turn (waiting at most [waitMs]), status byte, J-PAKE. Returns null
     * when refused, out of time, or the code was wrong (counted). Blocking IO; bound it by closing the socket.
     */
    suspend fun pair(input: DataInputStream, output: DataOutputStream, waitMs: Long): Pairing? {
        val slot = synchronized(lock) { (_inHandshake.value < maxInHandshake).also { if (it) _inHandshake.value++ } }
        if (!slot) {
            JamWire.writeStatus(output, JamWire.STATUS_BUSY)
            return null
        }
        try {
            // Only the wait for the turn is a suspension point; it alone is bounded by a coroutine timeout.
            withTimeoutOrNull(waitMs.coerceAtLeast(0)) { turn.lock() } ?: return null
            try {
                return pairInTurn(input, output)
            } finally {
                turn.unlock()
            }
        } finally {
            synchronized(lock) { _inHandshake.value-- }
        }
    }

    private fun pairInTurn(input: DataInputStream, output: DataOutputStream): Pairing? {
        val code = synchronized(lock) { code.takeUnless { _exhausted.value } }
        val status = when {
            code == null -> JamWire.STATUS_EXHAUSTED
            lockout.isLocked() -> JamWire.STATUS_LOCKED
            else -> JamWire.STATUS_OK
        }
        JamWire.writeStatus(output, status)
        if (code == null || status != JamWire.STATUS_OK) return null
        val keyingMaterial = try {
            JpakeHandshake.run(input, output, code, Role.Sender, "jam-host")
        } catch (e: DeviceSyncAuthException) {
            recordWrongCode(code)
            return null
        }
        return Pairing(SecureChannel(input, output, keyingMaterial, Role.Sender), code)
    }

    /** Counts a wrong guess against [code], unless the host has replaced it meanwhile. */
    private fun recordWrongCode(code: String) = synchronized(lock) {
        if (code != this.code) return
        lockout.recordFailure()
        if (++failures >= MAX_WRONG_CODES) _exhausted.value = true
    }

    private fun requireCode(code: String) =
        require(code.length == PairingCode.LENGTH && code.all { it in '0'..'9' }) { "Bad Jam code" }

    companion object {
        /** Wrong codes in total before the code is dead until [replaceCode] (the timed lock comes every 3). */
        const val MAX_WRONG_CODES = 10
    }
}
