package de.yurtemre.sonora.shared.devicesync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pairing lockout shared by device sync and the Jam (I7): [maxFailures] failed code checks start a cool-down of
 * [coolDownMs] (by [clock]) during which pairing is refused; starting the cool-down clears the count. Only a failed
 * check ([DeviceSyncAuthException]) is recorded; a connection refused because of the lock never counts. Thread-safe.
 */
internal class PairingLockout(
    private val clock: () -> Long,
    private val maxFailures: Int = DeviceSyncServer.MAX_FAILED_ATTEMPTS,
    private val coolDownMs: Long = DeviceSyncServer.COOL_DOWN_MS,
) {
    private var failures = 0
    private val _lockedUntilMs = MutableStateFlow(0L)

    /** Clock time until which pairing is refused; 0 before the first lock. */
    val lockedUntilMs: StateFlow<Long> = _lockedUntilMs.asStateFlow()

    @Synchronized
    fun isLocked(): Boolean = clock() < _lockedUntilMs.value

    /** Counts one failed check. Returns the end of the cool-down when this failure started one, else null. */
    @Synchronized
    fun recordFailure(): Long? {
        failures++
        if (failures < maxFailures) return null
        failures = 0
        return (clock() + coolDownMs).also { _lockedUntilMs.value = it }
    }

    /** Forgets earlier failures (device sync: the code was replaced, so they were against an old code). */
    @Synchronized
    fun resetFailures() {
        failures = 0
    }
}
