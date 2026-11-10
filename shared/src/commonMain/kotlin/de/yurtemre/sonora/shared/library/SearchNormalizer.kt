package de.yurtemre.sonora.shared.library

/** NFKC + lowercase + strip combining marks + katakana→hiragana (departs from Flutter's plain lowercase). */
expect fun normalizeForSearch(s: String): String

/** Shared tail of [normalizeForSearch] after Unicode normalization. */
internal fun foldKana(s: String): String = buildString(s.length) {
    for (c in s) append(if (c in '\u30A1'..'\u30F6') c - 0x60 else c)
}
