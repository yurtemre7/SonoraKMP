package de.yurtemre.sonora.shared.jam

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The wait for the join turn. The race the ownership flag guards against (the timeout firing after the lock was
 * acquired but before the timeout block returned) cannot be forced deterministically; this exercises the timeout
 * path with a held turn and checks nothing leaks.
 */
class JamJoinGateTest {
    @Test
    fun aJoinThatTimesOutWaitingForTheTurnLeavesNothingBehind() = runBlocking {
        val gate = JamJoinGate({ 0L }, maxInHandshake = 2)
        gate.start("482916")
        gate.turn.lock() // another join holds the turn
        val output = ByteArrayOutputStream()
        val result = gate.pair(DataInputStream(ByteArrayInputStream(ByteArray(0))), DataOutputStream(output), waitMs = 50)
        assertNull(result)
        assertEquals(0, output.size(), "nothing is sent before the turn")
        assertEquals(0, gate.inHandshake.value, "the join slot is released")
        gate.turn.unlock()
        assertTrue(gate.turn.tryLock(), "the turn is free again")
        gate.turn.unlock()
    }

    @Test
    fun aZeroOrNegativeWaitDoesNotTakeTheTurn() = runBlocking {
        val gate = JamJoinGate({ 0L }, maxInHandshake = 2)
        gate.start("482916")
        gate.turn.lock()
        assertNull(gate.pair(DataInputStream(ByteArrayInputStream(ByteArray(0))), DataOutputStream(ByteArrayOutputStream()), -5))
        assertEquals(0, gate.inHandshake.value)
        gate.turn.unlock()
        assertTrue(gate.turn.tryLock())
    }
}
