package de.yurtemre.sonora.jam

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest

/**
 * The optional background search for Jams: while this runs (the app is open, see MainActivity) and [enabled] is on,
 * [warm] holds the shared search. It never asks for a permission: without the local network grant
 * ([localNetworkAllowed]) it stays idle, and a grant given later is picked up the next time the app opens. Turning
 * the setting off releases the search at once. Cancelling releases it too.
 */
suspend fun runJamWarmup(
    enabled: Flow<Boolean>,
    localNetworkAllowed: () -> Boolean,
    warm: () -> AutoCloseable,
) {
    enabled.distinctUntilChanged().collectLatest { on ->
        if (!on || !localNetworkAllowed()) return@collectLatest
        val handle = warm()
        try {
            awaitCancellation()
        } finally {
            handle.close()
        }
    }
}
