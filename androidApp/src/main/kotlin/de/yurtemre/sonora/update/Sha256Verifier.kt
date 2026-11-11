package de.yurtemre.sonora.update

import de.yurtemre.sonora.shared.update.ChecksumParser
import java.io.File
import java.security.MessageDigest

/** Streaming SHA-256 check of a downloaded APK against its `.sha256` asset (UPD-08). */
object Sha256Verifier {
    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Parses [checksumText] (`sha256sum` format); returns null when it is malformed. */
    fun expected(checksumText: String, apkName: String): String? = ChecksumParser.parse(checksumText, apkName)

    /** True if [file] matches [expectedHex]; on mismatch the file is deleted. */
    fun verify(file: File, expectedHex: String): Boolean {
        val ok = sha256Hex(file).equals(expectedHex.trim(), ignoreCase = true)
        if (!ok) file.delete()
        return ok
    }
}
