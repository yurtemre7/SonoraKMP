package de.yurtemre.sonora.update

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Sha256VerifierTest {
    private fun tmp(): File = File.createTempFile("apk", ".apk").apply { writeText("abc"); deleteOnExit() }
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun matchKeepsFile() {
        val f = tmp()
        val expected = Sha256Verifier.expected("$abcHash  sonora-1.0.0.apk\n", "sonora-1.0.0.apk")
        assertEquals(abcHash, expected)
        assertTrue(Sha256Verifier.verify(f, expected!!.uppercase()))
        assertTrue(f.exists())
    }

    @Test fun mismatchDeletesFile() {
        val f = tmp()
        assertFalse(Sha256Verifier.verify(f, "0".repeat(64)))
        assertFalse(f.exists())
    }
}
