package de.yurtemre.sonora.shared.platform

import de.yurtemre.sonora.shared.repo.api.Clock

/** Wall clock backed by [System.currentTimeMillis]. */
object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
