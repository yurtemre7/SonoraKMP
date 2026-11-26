package de.yurtemre.sonora.devicesync

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ResolveQueueTest {
    private class Timer {
        class Task(val delay: Long, val block: () -> Unit) : AutoCloseable {
            var cancelled = false
            override fun close() { cancelled = true }
        }
        val tasks = mutableListOf<Task>()
        fun schedule(delay: Long, block: () -> Unit): AutoCloseable = Task(delay, block).also { tasks += it }
        /** Runs (and removes) the pending task with this delay. */
        fun fire(delay: Long) {
            val t = tasks.first { it.delay == delay && !it.cancelled }
            tasks.remove(t)
            t.block()
        }
    }

    private val timer = Timer()
    private val started = mutableListOf<String>()
    private val dones = HashMap<String, MutableList<(Boolean) -> Unit>>()

    private fun queue() = ResolveQueue<String>(
        key = { it },
        resolve = { item, done -> started += item; dones.getOrPut(item) { mutableListOf() } += done },
        schedule = timer::schedule,
        timeoutMs = 4000, maxAttempts = 3,
    )

    private fun answer(name: String, ok: Boolean) = dones.getValue(name).last()(ok)

    @Test fun resolvesOneAtATime() {
        val q = queue()
        q.enqueue("a"); q.enqueue("b")
        assertEquals(listOf("a"), started)
        answer("a", true)
        assertEquals(listOf("a", "b"), started)
    }

    @Test fun aNameAlreadyQueuedOrResolvingIsNotQueuedTwice() {
        val q = queue()
        q.enqueue("a"); q.enqueue("a"); q.enqueue("b"); q.enqueue("b")
        answer("a", true)
        answer("b", true)
        assertEquals(listOf("a", "b"), started)
    }

    @Test fun aResolvedNameCanBeQueuedAgainAfterItWasLost() {
        val q = queue()
        q.enqueue("a"); answer("a", true)
        q.remove("a"); q.enqueue("a")
        assertEquals(listOf("a", "a"), started)
    }

    @Test fun aFailureIsRetriedAfterABackoffAtTheBack() {
        val q = queue()
        q.enqueue("a"); q.enqueue("b")
        answer("a", false)
        assertEquals(listOf("a", "b"), started) // b first, a waits for its backoff
        answer("b", true)
        timer.fire(300)
        assertEquals(listOf("a", "b", "a"), started)
    }

    @Test fun givesUpAfterMaxAttempts() {
        val q = queue()
        q.enqueue("a")
        answer("a", false); timer.fire(300)
        answer("a", false); timer.fire(600)
        answer("a", false)
        assertEquals(3, started.count { it == "a" })
        assertTrue(timer.tasks.none { !it.cancelled && it.delay in setOf(300L, 600L, 900L) })
    }

    @Test fun aSilentAttemptTimesOutAndTheNextOneRuns() {
        val q = queue()
        q.enqueue("a"); q.enqueue("b")
        timer.fire(4000)
        assertEquals(listOf("a", "b"), started)
        answer("a", true) // a late answer of the abandoned attempt changes nothing
        assertEquals(listOf("a", "b"), started)
    }

    @Test fun aThrowingResolveCountsAsAFailure() {
        var calls = 0
        val q = ResolveQueue<String>({ it }, { _, _ -> calls++; error("boom") }, timer::schedule)
        q.enqueue("a")
        assertEquals(1, calls)
        timer.fire(300)
        assertEquals(2, calls)
    }

    @Test fun aLostServiceIsNeitherResolvedNorRetried() {
        val q = queue()
        q.enqueue("a"); q.enqueue("b")
        q.remove("b")
        answer("a", true)
        assertEquals(listOf("a"), started)
        q.enqueue("c"); q.remove("c")
        // c was started? only if it ran before removal
        answer("c", false)
        assertTrue(timer.tasks.none { !it.cancelled && it.delay == 300L })
    }

    @Test fun closeStopsEverything() {
        val q = queue()
        q.enqueue("a"); q.enqueue("b")
        q.close()
        answer("a", true)
        q.enqueue("c")
        assertEquals(listOf("a"), started)
    }
}

class RegistrationRetryTest {
    @Test fun retriesThreeTimesWithGrowingDelaysThenGivesUp() {
        assertEquals(500L, RegistrationRetry.delayMs(0))
        assertEquals(1_500L, RegistrationRetry.delayMs(1))
        assertEquals(3_000L, RegistrationRetry.delayMs(2))
        assertEquals(null, RegistrationRetry.delayMs(3))
    }
}
