package de.yurtemre.sonora.shared.update

/** Parses `sha256sum` output: `<64 hex>` optionally followed by whitespace + (`*`)filename. */
object ChecksumParser {
    private val REGEX = Regex("""^([0-9A-Fa-f]{64})(?:\s+\*?(\S.*))?$""")

    /** Returns the lower-case hex digest, or null if malformed. If [expectedFileName] is given and a file name is present, it must match. */
    fun parse(text: String, expectedFileName: String? = null): String? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val m = REGEX.matchEntire(line) ?: return null
        val name = m.groupValues[2].trim()
        if (expectedFileName != null && name.isNotEmpty() && name.substringAfterLast('/') != expectedFileName) return null
        return m.groupValues[1].lowercase()
    }
}
