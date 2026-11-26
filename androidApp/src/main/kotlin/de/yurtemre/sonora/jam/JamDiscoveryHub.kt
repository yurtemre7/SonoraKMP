package de.yurtemre.sonora.jam

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * One shared LAN search for every user of it: the join screen ([discover]) and the optional warm-up that keeps the
 * search running while the app is open ([warm]), so the Jams are already listed when the join screen opens.
 *
 * The search ([upstream], a fresh NSD discovery per start) runs while at least one user holds it, and stops
 * [graceMs] after the last one lets go, so a rotation or a quick trip between screens does not restart it. A user
 * that arrives while it runs gets the Jams found so far at once. A failed search ([IOException]) is handed to every
 * current user; the next user starts a new one.
 */
class JamDiscoveryHub(
    private val scope: CoroutineScope,
    private val upstream: () -> Flow<List<DiscoveredJam>>,
    private val graceMs: Long = GRACE_MS,
) {
    private sealed interface Result {
        data class Found(val jams: List<DiscoveredJam>) : Result
        data class Failed(val error: Throwable) : Result
    }

    private val lock = Any()
    private val result = MutableStateFlow<Result?>(null)
    private var users = 0
    private var search: Job? = null
    private var stopper: Job? = null

    /** The Jams on the LAN, newest list first; fails like [upstream]. Collecting it holds the search. */
    fun discover(): Flow<List<DiscoveredJam>> = flow {
        acquire()
        try {
            result.collect {
                when (it) {
                    null -> Unit
                    is Result.Found -> emit(it.jams)
                    is Result.Failed -> throw it.error
                }
            }
        } finally {
            release()
        }
    }

    /** Keeps the search running until the returned handle is closed (closing twice is a no-op). */
    fun warm(): AutoCloseable {
        acquire()
        var closed = false
        return AutoCloseable {
            val first = synchronized(lock) { !closed.also { closed = true } }
            if (first) release()
        }
    }

    private fun acquire() = synchronized(lock) {
        users++
        stopper?.cancel()
        stopper = null
        if (search?.isActive != true) {
            result.value = null // the list of an earlier search is stale
            search = scope.launch {
                try {
                    upstream().collect { result.value = Result.Found(it) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    result.value = Result.Failed(e)
                }
            }
        }
    }

    private fun release() = synchronized(lock) {
        users--
        if (users > 0) return@synchronized
        stopper?.cancel()
        stopper = scope.launch {
            delay(graceMs)
            synchronized(lock) {
                if (users == 0) {
                    search?.cancel()
                    search = null
                    result.value = null
                }
            }
        }
    }

    companion object {
        const val GRACE_MS = 5_000L
    }
}
