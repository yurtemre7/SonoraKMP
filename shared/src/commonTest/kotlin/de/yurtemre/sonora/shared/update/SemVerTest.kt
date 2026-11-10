package de.yurtemre.sonora.shared.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemVerTest {
    private fun v(s: String) = SemVer.parse(s)!!

    @Test fun semverOrgChain() {
        val chain = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "2.0.0", "2.1.0", "2.1.1",
        ).map(::v)
        chain.zipWithNext().forEach { (a, b) -> assertTrue(a < b, "$a < $b") }
    }

    @Test fun prefixAndBuild() {
        assertEquals(v("1.2.3"), v("v1.2.3"))
        assertEquals(0, v("V1.2.3+abc.5").compareTo(v("1.2.3")))
        assertEquals("1.30.0-beta.1", v("v1.30.0-beta.1").toString())
    }

    @Test fun devStrip() {
        assertEquals(v("1.28.1"), SemVer.parseLocal("1.28.1-dev"))
        assertEquals(v("1.29.0-beta.1"), SemVer.parseLocal("1.29.0-beta.1-dev"))
    }

    @Test fun invalid() {
        listOf("1.2", "x1.2.3", "01.2.3", "1.2.3-01", "", "1.2.3-").forEach { assertNull(SemVer.parse(it), it) }
    }

    @Test fun checksum() {
        val h = "A".repeat(64)
        assertEquals(h.lowercase(), ChecksumParser.parse(h))
        assertEquals(h.lowercase(), ChecksumParser.parse("$h  sonora-1.0.0.apk\n", "sonora-1.0.0.apk"))
        assertEquals(h.lowercase(), ChecksumParser.parse("$h *sonora-1.0.0.apk"))
        assertNull(ChecksumParser.parse("$h  other.apk", "sonora-1.0.0.apk"))
        assertNull(ChecksumParser.parse("abc"))
        assertNull(ChecksumParser.parse("g".repeat(64)))
    }
}
