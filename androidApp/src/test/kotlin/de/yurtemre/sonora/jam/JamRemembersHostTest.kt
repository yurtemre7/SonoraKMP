package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.JamError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A host a guest joined is handed on to be remembered (faster next search); a failed join is not. */
@OptIn(ExperimentalCoroutinesApi::class)
class JamRemembersHostTest : JamControllerTestBase() {
    @Test fun successfulJoinReportsTheHost() = runTest {
        val jam = controller()
        joined(jam)
        assertEquals(listOf(Triple("10.0.0.2", 41_234, "Host phone")), joinedHosts)
    }

    @Test fun failedJoinReportsNothing() = runTest {
        val jam = controller()
        connector.joinError = JamError.WrongCode
        jam.join("10.0.0.2", 41_234, "000000", "Host phone")
        runCurrent()
        assertTrue(joinedHosts.isEmpty())
    }
}
