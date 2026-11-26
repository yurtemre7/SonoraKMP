package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.JAM_PROTOCOL_VERSION
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class JamDiscoveryTest {
    @Test fun serviceTypeIsTheJamOne() {
        assertEquals("_sonora-jam._tcp", JamDiscovery.SERVICE_TYPE)
    }

    @Test fun txtRecordCarriesOnlyVersionAndName() {
        assertEquals(mapOf("v" to JAM_PROTOCOL_VERSION.toString(), "name" to "Emre's Pixel"), JamDiscovery.txtRecord("Emre's Pixel"))
    }

    @Test fun txtNameIsTruncatedLikeDeviceSync() {
        assertEquals(60, JamDiscovery.txtRecord("a".repeat(100)).getValue("name").length)
    }

    @Test fun serviceNameUsesFirstEightHexLowercase() {
        assertEquals("SonoraJam-abcd1234", JamDiscovery.serviceNameFor("ABCD-1234-ef56"))
    }

    @Test fun manualJoinParsesIpAndPort() {
        assertEquals("192.168.1.20" to 41234, JamDiscovery.parseManual(" 192.168.1.20:41234 "))
        assertNull(JamDiscovery.parseManual("192.168.1.20"))
    }
}
