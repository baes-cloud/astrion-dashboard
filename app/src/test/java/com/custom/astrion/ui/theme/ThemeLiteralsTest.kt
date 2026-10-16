package com.custom.astrion.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Colours and font sizes come from the theme, not literals in the cards. */
class ThemeLiteralsTest {
    private val sources = File("src/main/java/com/custom/astrion").walkTopDown()
        .filter { it.extension == "kt" && "/ui/theme/" !in it.invariantSeparatorsPath }
        .toList()

    private fun offenders(pattern: Regex, allow: (String) -> Boolean = { false }) = sources.flatMap { f ->
        f.readLines().mapIndexedNotNull { i, line ->
            if (pattern.containsMatchIn(line) && !allow(line)) "${f.name}:${i + 1}: ${line.trim()}" else null
        }
    }

    @Test
    fun noColourLiterals() {
        assertTrue(sources.isNotEmpty())
        // Bulb colour presets are data sent to the light, not theme colours.
        val found = offenders(Regex("""Color\(0x[0-9A-Fa-f]{8}\)""")) { "// purple" in it || "// blue" in it || "// peach" in it }
        assertTrue("colour literals outside ui/theme:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun noFontSizeLiterals() {
        val found = offenders(Regex("""fontSize = [0-9.]+\.sp"""))
        assertTrue("fontSize literals outside ui/theme:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
