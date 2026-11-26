package de.yurtemre.sonora.shared.net

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerSocketsTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test fun bindsThePreferredPortWhenFree() {
        val port = freePort()
        bindServerSocket(port).use { assertEquals(port, it.localPort) }
    }

    @Test fun fallsBackToAnotherPortWhenThePreferredOneIsTaken() {
        ServerSocket(0).use { taken ->
            bindServerSocket(taken.localPort).use {
                assertNotEquals(taken.localPort, it.localPort)
                assertTrue(it.localPort > 0)
            }
        }
    }

    @Test fun unsetOrPrivilegedPortMeansAnyFreePort() {
        bindServerSocket(0).use { assertTrue(it.localPort >= 1024) }
        bindServerSocket(80).use { assertTrue(it.localPort >= 1024) }
        bindServerSocket(-5).use { assertTrue(it.localPort >= 1024) }
    }
}
