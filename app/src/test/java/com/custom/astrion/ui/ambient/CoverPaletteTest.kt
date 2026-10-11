package com.custom.astrion.ui.ambient

import com.custom.astrion.ui.ambient.scene.CoverPalette
import com.custom.astrion.ui.ambient.scene.OklabConvert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPaletteTest {

    /** Repeat each colour so clusters have real populations. */
    private fun pixels(vararg colours: Int, each: Int = 40): IntArray {
        val out = IntArray(colours.size * each)
        var i = 0
        for (c in colours) repeat(each) { out[i++] = c }
        return out
    }

    @Test fun distinctColoursStayDistinct() {
        val red = 0xE03030; val blue = 0x3040E0; val green = 0x30C040
        val swatches = CoverPalette.of(pixels(red, blue, green))
        assertEquals(3, swatches.size)
        // Every pair is at least the distinctness floor apart.
        for (i in swatches.indices) for (j in i + 1 until swatches.size) {
            assertTrue(swatches[i].color.distance(swatches[j].color) >= CoverPalette.MIN_DISTANCE)
        }
        // Each input colour is matched by some swatch.
        for (c in listOf(red, blue, green)) {
            val target = OklabConvert.toOklab(c)
            assertTrue(swatches.any { it.color.distance(target) < 0.08f })
        }
    }

    @Test fun greyscaleCoverGivesNeutrals() {
        val greys = CoverPalette.of(pixels(0x101010, 0x555555, 0x999999, 0xDDDDDD))
        assertTrue(greys.isNotEmpty())
        assertTrue(greys.all { it.color.chroma < 0.02f })
    }

    @Test fun nearDuplicatesMerge() {
        // Two almost-identical blues collapse to one swatch.
        val swatches = CoverPalette.of(pixels(0x3040E0, 0x3242E2))
        assertEquals(1, swatches.size)
    }

    @Test fun chromaOutweighsSize() {
        // A large dull grey field with a small vivid orange splash: the orange
        // should outrank the grey despite covering far less of the image.
        val px = pixels(0x707070, each = 200) + pixels(0xFF7A1A, each = 40)
        val swatches = CoverPalette.of(px)
        assertTrue(swatches.isNotEmpty())
        assertTrue("vivid colour should lead", swatches.first().color.chroma > 0.05f)
    }

    @Test fun emptyIsEmpty() {
        assertTrue(CoverPalette.of(IntArray(0)).isEmpty())
    }
}
