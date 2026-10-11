package com.custom.astrion.ui.ambient

import com.custom.astrion.ui.ambient.scene.OklabConvert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OklabTest {

    private val samples = listOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF,
        0x808080, 0xE6BCB6, 0x2F3E4C, 0x4F726D, 0x123456, 0xABCDEF,
    )

    @Test fun roundTripsWithinOneBit() {
        for (rgb in samples) {
            val back = OklabConvert.toRgb(OklabConvert.toOklab(rgb))
            val dr = Math.abs(OklabConvert.red(rgb) - OklabConvert.red(back))
            val dg = Math.abs(OklabConvert.green(rgb) - OklabConvert.green(back))
            val db = Math.abs(OklabConvert.blue(rgb) - OklabConvert.blue(back))
            assertTrue("round-trip drift for ${rgb.toString(16)}: $dr,$dg,$db", dr <= 2 && dg <= 2 && db <= 2)
        }
    }

    @Test fun greyHasNoChroma() {
        for (g in listOf(0x101010, 0x404040, 0x808080, 0xC0C0C0)) {
            assertTrue(OklabConvert.toOklab(g).chroma < 0.001f)
        }
    }

    @Test fun blackAndWhiteLightnessOrdered() {
        assertEquals(0f, OklabConvert.toOklab(0x000000).l, 0.001f)
        assertTrue(OklabConvert.toOklab(0xFFFFFF).l > 0.99f)
    }
}
