package de.yurtemre.sonora.shared.model

/** The sections of the Home dashboard that can be reordered and hidden (the title and greeting always stay on top). */
enum class HomeSection(val key: String) {
    JumpBack("jump_back"),
    Actions("actions"),
    Glance("glance"),
    Favorite("favorite"),
    Tiles("tiles"),
    Favorites("favorites"),
    Recommended("recommended"),
    TimeMix("time_mix"),
    Weekly("weekly"),
    Recent("recent"),
}

/** One row of the Home layout: a [section] and whether it is shown. */
data class HomeSectionState(val section: HomeSection, val visible: Boolean = true)

/**
 * The stored Home layout (`home_layout`, KMP-only, per device, not in backups), written as `key:1,key:0` in display
 * order. Unknown keys and repeats are dropped; sections missing from the string (for example ones added by a later
 * version) are appended at the end, shown. An empty or missing value is the default order.
 */
object HomeLayout {
    val default: List<HomeSectionState> = HomeSection.entries.map { HomeSectionState(it) }

    fun parse(raw: String?): List<HomeSectionState> {
        val byKey = HomeSection.entries.associateBy { it.key }
        val seen = LinkedHashSet<HomeSection>()
        val out = ArrayList<HomeSectionState>()
        raw.orEmpty().split(',').forEach { part ->
            val key = part.substringBefore(':').trim()
            val section = byKey[key] ?: return@forEach
            if (!seen.add(section)) return@forEach
            out += HomeSectionState(section, visible = part.substringAfter(':', "1").trim() != "0")
        }
        HomeSection.entries.filter { it !in seen }.forEach { out += HomeSectionState(it) }
        return out
    }

    fun serialize(layout: List<HomeSectionState>): String =
        layout.joinToString(",") { "${it.section.key}:${if (it.visible) 1 else 0}" }
}
