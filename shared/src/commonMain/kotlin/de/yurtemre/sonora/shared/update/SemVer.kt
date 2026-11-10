package de.yurtemre.sonora.shared.update

/** Semantic version per semver.org; build metadata ignored for ordering. */
data class SemVer(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemVer> {
    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return compareValues(other.preRelease.size.coerceAtMost(1), preRelease.size.coerceAtMost(1))
        }
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val c = compareIdent(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return compareValues(preRelease.size, other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (isPreRelease) "-" + preRelease.joinToString(".") else ""

    companion object {
        private val REGEX = Regex(
            """^[vV]?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
        )

        fun parse(text: String): SemVer? {
            val m = REGEX.matchEntire(text.trim()) ?: return null
            val (ma, mi, pa, pre) = m.destructured
            val ids = if (pre.isEmpty()) emptyList() else pre.split('.')
            if (ids.any { it.length > 1 && it.all(Char::isDigit) && it[0] == '0' }) return null
            return SemVer(
                ma.toLongOrNull() ?: return null,
                mi.toLongOrNull() ?: return null,
                pa.toLongOrNull() ?: return null,
                ids,
            )
        }

        /** Parses the local VERSION_NAME, stripping a trailing `-dev`. */
        fun parseLocal(versionName: String): SemVer? = parse(versionName.trim().removeSuffix("-dev"))

        private fun compareIdent(a: String, b: String): Int {
            val an = a.all(Char::isDigit)
            val bn = b.all(Char::isDigit)
            return when {
                an && bn -> if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                an -> -1
                bn -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
