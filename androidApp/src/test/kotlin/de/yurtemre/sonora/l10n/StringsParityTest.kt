package de.yurtemre.sonora.l10n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Every translatable en string/plurals key exists in every translation (and vice versa) with the same format arguments. */
class StringsParityTest {

    private val res = File("src/main/res").also { check(it.isDirectory) { "run from the androidApp module dir" } }

    /** key -> sorted set of format args such as "1$s", "2$d" (plain "%d" counts as "1$d"). */
    private fun load(dir: String): Map<String, Set<String>> {
        val files = File(res, dir).listFiles { f -> f.name.matches(Regex("strings.*\\.xml")) }.orEmpty()
        assertTrue("no string files in $dir", files.isNotEmpty())
        val out = mutableMapOf<String, Set<String>>()
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (file in files) {
            val nodes = builder.parse(file).documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.tagName !in setOf("string", "plurals") || e.getAttribute("translatable") == "false") continue
                val args = ARG.findAll(e.textContent.replace("%%", ""))
                    .map { (it.groupValues[1].ifEmpty { "1" }) + "$" + it.groupValues[2] }
                    .toSortedSet()
                assertTrue("duplicate key ${e.getAttribute("name")} in $dir", out.put(e.getAttribute("name"), args) == null)
            }
        }
        return out
    }

    @Test
    fun everyLanguageMatchesEnglishInKeysAndFormatArguments() {
        val en = load("values")
        for (dir in translationDirs()) {
            val other = load(dir)
            assertEquals("missing in $dir", emptySet<String>(), en.keys - other.keys)
            assertEquals("missing in values (extra in $dir)", emptySet<String>(), other.keys - en.keys)
            val mismatches = en.filter { (k, args) -> k in other && other[k] != args }
                .map { (k, args) -> "$k: en=$args $dir=${other[k]}" }
            assertEquals(dir, emptyList<String>(), mismatches)
        }
    }

    private fun translationDirs(): List<String> =
        res.list().orEmpty().filter { it.startsWith("values-") && File(res, it).listFiles().orEmpty().any { f -> f.name.startsWith("strings") } }.sorted()

    private companion object {
        val ARG = Regex("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([sdf])")
    }
}
