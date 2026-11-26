package de.yurtemre.sonora.devicesync

/**
 * Resolves discovered NSD services one at a time (before Android 14 `resolveService` allows a single active resolve).
 * Pure logic, no Android classes: the platform call and the timer are passed in, so it is unit-tested.
 *
 *  - a name already queued, resolving or waiting for a retry is not queued twice (NSD re-announces per interface);
 *  - an attempt that never calls back is abandoned after [timeoutMs], so one dead host cannot block the others;
 *  - a failed attempt is retried at the back of the queue after a growing delay, up to [maxAttempts] in total;
 *  - [remove] drops a lost service from every state; its in-flight result is ignored, not retried.
 *
 * [resolve] starts one attempt and must report it exactly once through `done(success)` (later calls are ignored).
 * It runs outside the lock and may throw: that counts as a failed attempt.
 */
internal class ResolveQueue<I : Any>(
    private val key: (I) -> String,
    private val resolve: (item: I, done: (success: Boolean) -> Unit) -> Unit,
    private val schedule: (delayMs: Long, block: () -> Unit) -> AutoCloseable,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    private class Entry<I>(val item: I, val name: String, var attempts: Int = 0)

    private val lock = Any()
    private val queue = ArrayDeque<Entry<I>>()
    private val pending = HashSet<String>() // queued, resolving or waiting for a retry
    private var current: Entry<I>? = null
    private var currentToken: Any? = null
    private var timeout: AutoCloseable? = null
    private var closed = false

    fun enqueue(item: I) {
        val name = key(item)
        synchronized(lock) {
            if (closed || !pending.add(name)) return
            queue.addLast(Entry(item, name))
        }
        pump()
    }

    fun remove(name: String) {
        synchronized(lock) {
            pending.remove(name)
            queue.removeAll { it.name == name }
        }
    }

    fun close() {
        val t = synchronized(lock) {
            closed = true
            queue.clear()
            pending.clear()
            timeout.also { timeout = null }
        }
        runCatching { t?.close() }
    }

    private fun pump() {
        val entry: Entry<I>
        val token = Any()
        synchronized(lock) {
            if (closed || current != null) return
            entry = queue.removeFirstOrNull() ?: return
            current = entry
            currentToken = token
        }
        val watchdog = schedule(timeoutMs) { finish(entry, token, false) }
        val stale = synchronized(lock) {
            if (currentToken === token) { timeout = watchdog; false } else true // finished/closed meanwhile
        }
        if (stale) runCatching { watchdog.close() }
        try {
            resolve(entry.item) { ok -> finish(entry, token, ok) }
        } catch (_: RuntimeException) {
            finish(entry, token, false)
        }
    }

    private fun finish(entry: Entry<I>, token: Any, success: Boolean) {
        var retryIn = -1L
        val t: AutoCloseable?
        synchronized(lock) {
            if (currentToken !== token) return // already finished (timeout) or closed
            currentToken = null
            current = null
            t = timeout.also { timeout = null }
            if (success || entry.name !in pending) {
                pending.remove(entry.name)
            } else {
                entry.attempts++
                if (entry.attempts >= maxAttempts) pending.remove(entry.name) else retryIn = BACKOFF_MS * entry.attempts
            }
        }
        runCatching { t?.close() }
        if (retryIn >= 0) {
            schedule(retryIn) {
                synchronized(lock) { if (!closed && entry.name in pending) queue.addLast(entry) }
                pump()
            }
        }
        pump()
    }

    companion object {
        const val TIMEOUT_MS = 4_000L
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 300L
    }
}
