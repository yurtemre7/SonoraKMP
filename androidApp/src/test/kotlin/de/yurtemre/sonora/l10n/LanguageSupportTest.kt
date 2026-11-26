package de.yurtemre.sonora.l10n

import de.yurtemre.sonora.shared.model.AppLocale
import de.yurtemre.sonora.shared.model.AppSettings
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** A language the picker offers is fully wired: resources, locale filter and the greetings that make it feel local. */
class LanguageSupportTest {

    private val res = File("src/main/res").also { check(it.isDirectory) { "run from the androidApp module dir" } }
    private val languages = AppLocale.entries.filter { it != AppLocale.System }.map { it.storageName }

    private fun strings(dir: String): Map<String, String> {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val out = mutableMapOf<String, String>()
        File(res, dir).listFiles { f -> f.name.matches(Regex("strings.*\\.xml")) }.orEmpty().forEach { file ->
            val nodes = builder.parse(file).documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
            }
        }
        return out
    }

    @Test
    fun appStartsInFollowSystemMode() {
        assertEquals(AppLocale.System, AppSettings().appLocale)
        assertEquals(AppLocale.System, AppLocale.fromStorage(null))
        assertEquals(AppLocale.System, AppLocale.fromStorage("xx"))
        assertEquals("system", AppLocale.System.storageName)
    }

    @Test
    fun everyPickerLanguageHasResourcesAndALocaleFilter() {
        val filters = File("build.gradle.kts").readText()
            .let { Regex("localeFilters\\s*\\+=\\s*listOf\\(([^)]*)\\)").find(it)!!.groupValues[1] }
            .let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        for (tag in languages) {
            assertTrue("no strings for $tag", tag == "en" || File(res, "values-$tag/strings.xml").isFile)
            assertTrue("$tag missing in localeFilters", tag in filters)
        }
        assertEquals("localeFilters lists a language the picker does not offer", languages.toSet(), filters)
    }

    @Test
    fun everyLanguageHasAGreetingForEachPartOfTheDayThatAddressesTheUser() {
        val keys = listOf("good_morning", "good_afternoon", "good_evening", "good_night")
        for (tag in languages) {
            val s = strings(if (tag == "en") "values" else "values-$tag")
            val greetings = keys.map { s.getValue(it) }
            greetings.forEachIndexed { i, text -> assertTrue("${keys[i]} ($tag) must contain the name", "%1\$s" in text) }
            assertEquals("greetings in $tag should differ by time of day", greetings.size, greetings.toSet().size)
            assertTrue("greetings in $tag should stay short enough for the header", greetings.all { it.length <= 32 })
        }
    }

    @Test
    fun translationsOtherThanEnglishAreActuallyTranslated() {
        val en = strings("values")
        val brandOrShared = setOf(
            "app_title", "mfx", "mfx_nightcore", "eq_pop", "eq_rock", "color_source_material_you", "dev_badge",
            "settings_title_style_sample", "info2_font_name", "info2_font_license", // a song title, a font name and its licence
        )
        for (tag in languages.filter { it != "en" }) {
            val other = strings("values-$tag")
            val untranslated = en.filter { (k, v) -> other[k] == v && v.any { it.isLetter() } && k !in brandOrShared && v.length > 14 }.keys
            assertEquals("$tag still has English text for these keys", emptySet<String>(), untranslated)
        }
    }
}
