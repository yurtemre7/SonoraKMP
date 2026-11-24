package de.yurtemre.sonora.shared.jam

/**
 * NTP-style offset estimate: of the first [WINDOW] valid samples of a window, the lowest-RTT one wins.
 *
 * The very first window publishes its best-so-far offset after every valid sample so a Jam can start quickly.
 * After [reset] samples go to a pending window; the committed offset is replaced only when that window completes
 * ([WINDOW] valid samples) or when the caller calls [commit] (e.g. after a timeout) with at least one pending sample.
 */
class ClockSync {
    private var count = 0
    private var pendingRtt = Long.MAX_VALUE
    private var pendingOffset: Long? = null
    private var committed: Long? = null
    private var live = true

    fun addSample(sentMs: Long, hostMs: Long, receivedMs: Long) {
        if (count >= WINDOW) return
        val rtt = receivedMs - sentMs
        if (rtt < 0) return
        count++
        if (rtt < pendingRtt) {
            pendingRtt = rtt
            // sent + rtt/2 == (sent + received)/2 without overflowing for rtt >= 0.
            pendingOffset = hostMs - (sentMs + rtt / 2)
        }
        if (live || count == WINDOW) commit()
    }

    /** Applies the best pending sample to the committed offset; a no-op when none is pending. */
    fun commit() {
        pendingOffset?.let { committed = it }
    }

    /** Starts a fresh sample window; the committed offset stays until that window completes or [commit] is called. */
    fun reset() {
        count = 0
        pendingRtt = Long.MAX_VALUE
        pendingOffset = null
        live = committed == null
    }

    fun offsetMs(): Long? = committed

    /** Host time to local time; the identity until [offsetMs] is non-null. */
    fun toLocal(hostTimeMs: Long): Long = hostTimeMs - (committed ?: 0L)

    companion object {
        const val WINDOW = 8
    }
}
