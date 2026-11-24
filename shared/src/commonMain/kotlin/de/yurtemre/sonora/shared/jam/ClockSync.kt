package de.yurtemre.sonora.shared.jam

/**
 * NTP-style offset estimate: of the first [WINDOW] valid samples since the last [reset], the lowest-RTT one wins.
 * [reset] starts a fresh window but keeps the previous offset until the new window yields a sample.
 */
class ClockSync {
    private var count = 0
    private var bestRtt = Long.MAX_VALUE
    private var offset: Long? = null

    fun addSample(sentMs: Long, hostMs: Long, receivedMs: Long) {
        if (count >= WINDOW) return
        val rtt = receivedMs - sentMs
        if (rtt < 0) return
        count++
        if (rtt < bestRtt) {
            bestRtt = rtt
            // sent + rtt/2 == (sent + received)/2 without overflowing for rtt >= 0.
            offset = hostMs - (sentMs + rtt / 2)
        }
    }

    /** Starts a fresh sample window; the current offset stays until the new window has a sample. */
    fun reset() {
        count = 0
        bestRtt = Long.MAX_VALUE
    }

    fun offsetMs(): Long? = offset

    fun toLocal(hostTimeMs: Long): Long = hostTimeMs - (offset ?: 0L)

    companion object {
        const val WINDOW = 8
    }
}
