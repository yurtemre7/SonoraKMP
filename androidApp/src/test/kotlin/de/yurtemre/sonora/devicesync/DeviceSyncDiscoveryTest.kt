package de.yurtemre.sonora.devicesync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSyncDiscoveryTest {
    @Test fun parseManualAcceptsIpv4AndPort() {
        assertEquals("192.168.1.20" to 8080, DeviceSyncDiscovery.parseManual("192.168.1.20:8080"))
        assertEquals("0.0.0.1" to 1, DeviceSyncDiscovery.parseManual("0.0.0.1:1"))
        assertEquals("255.255.255.254" to 65535, DeviceSyncDiscovery.parseManual("255.255.255.254:65535"))
    }

    @Test fun parseManualTrimsWhitespace() {
        assertEquals("10.0.0.5" to 4000, DeviceSyncDiscovery.parseManual("  10.0.0.5:4000 \n"))
    }

    @Test fun parseManualRejectsBadAddresses() {
        listOf(
            "", "   ", "192.168.1.20", "192.168.1.20:", ":8080", "192.168.1:80", "192.168.1.2.3:80",
            "256.1.1.1:80", "1.1.1.-1:80", "a.b.c.d:80", "1..1.1:80", "localhost:80", "example.com:80",
            "[::1]:80", "::1:80", "fe80::1:80", "192.168.1.20:0", "192.168.1.20:65536", "192.168.1.20:-5",
            "192.168.1.20:abc", "192.168.1.20:80:90", "192.168.1.20 :80", "192.168. 1.20:80",
            "0.0.0.0:80", "255.255.255.255:80", "+1.1.1.1:80", "1.1.1.1:+80", "http://1.1.1.1:80", "١.١.١.١:80",
        ).forEach { assertNull(DeviceSyncDiscovery.parseManual(it), "should reject '$it'") }
    }

    @Test fun serviceNameUsesFirstFourHexLowercase() {
        assertEquals("Sonora-abcd", DeviceSyncDiscovery.serviceNameFor("ABCD1234-0000"))
        assertEquals("Sonora-1234", DeviceSyncDiscovery.serviceNameFor("12-34-56"))
        assertEquals("Sonora-ab", DeviceSyncDiscovery.serviceNameFor("A-B"))
        assertEquals("Sonora-", DeviceSyncDiscovery.serviceNameFor(""))
    }

    @Test fun truncateNameKeepsShortNames() {
        assertEquals("Pixel 8", DeviceSyncDiscovery.truncateName("Pixel 8"))
    }

    @Test fun truncateNameCutsOnCharacterBoundary() {
        val ascii = "a".repeat(100)
        assertEquals(60, DeviceSyncDiscovery.truncateName(ascii).toByteArray().size)
        // 'ä' is 2 bytes: 31 of them = 62 bytes -> 30 fit.
        val umlauts = "ä".repeat(31)
        assertEquals("ä".repeat(30), DeviceSyncDiscovery.truncateName(umlauts))
        // Surrogate pair (4 bytes) must not be split: 58 ascii + emoji = 62 bytes -> emoji dropped.
        val emoji = "a".repeat(58) + "😀"
        assertEquals("a".repeat(58), DeviceSyncDiscovery.truncateName(emoji))
        assertTrue(DeviceSyncDiscovery.truncateName("😀".repeat(20)).toByteArray().size <= 60)
    }
}
