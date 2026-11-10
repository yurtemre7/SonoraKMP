package de.yurtemre.sonora.shared.library

import java.text.Normalizer

private val COMBINING = Regex("""\p{Mn}+""")

actual fun normalizeForSearch(s: String): String {
    val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
    val stripped = Normalizer.normalize(nfkc, Normalizer.Form.NFD).replace(COMBINING, "")
    return foldKana(Normalizer.normalize(stripped, Normalizer.Form.NFC))
}
