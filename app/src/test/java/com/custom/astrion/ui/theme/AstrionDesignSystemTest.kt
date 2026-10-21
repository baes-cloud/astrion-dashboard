package com.custom.astrion.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AstrionDesignSystemTest {

    private val colors = AstrionDesignSystem.defaultColors
    private val typography = AstrionDesignSystem.defaultTypography
    private val elevation = AstrionDesignSystem.defaultElevation
    private val surfaces = AstrionDesignSystem.defaultSurfaces
    private val states = AstrionDesignSystem.defaultStates

    @Test
    fun surfaceTokensMatchModelOracle() {
        assertEquals(Color(0xFF151B21), colors.surfacePrimary)
        assertEquals(Color(0xFF1C2630), colors.surfaceCard)
        assertEquals(Color(0xFF243140), colors.surface)
        assertEquals(Color(0xFF161F28), colors.surfaceSunken)
        assertEquals(Color(0xFF8CBDB5), colors.accent)
        assertEquals(Color(0xFFEEF2EF), colors.onSurface)
        assertEquals(Color(0xFFA9BAB6), colors.onSurfaceMuted)
        assertEquals(0.4f, colors.unavailableAlpha, 0.001f)
    }

    @Test
    fun typographyRolesMatchSpecification() {
        assertEquals(34.sp, typography.display.fontSize)
        assertEquals(40.sp, typography.display.lineHeight)

        assertEquals(17.sp, typography.title.fontSize)
        assertEquals(22.sp, typography.title.lineHeight)

        assertEquals(14.sp, typography.body.fontSize)
        assertEquals(18.sp, typography.body.lineHeight)

        assertEquals(12.sp, typography.label.fontSize)
        assertEquals(16.sp, typography.label.lineHeight)
    }

    @Test
    fun elevationTokensMatchSpecification() {
        assertEquals(0.dp, elevation.flat)
        assertEquals(2.dp, elevation.raised)
        assertEquals(8.dp, elevation.floating)
        assertEquals(5.dp, elevation.tactileLip)
    }

    @Test
    fun surfacesTokensShapesAndAlpha() {
        assertEquals(18.dp, surfaces.cardCornerRadius)
        assertEquals(14.dp, surfaces.cardCornerRadiusSmall)
        assertEquals(24.dp, surfaces.dialogCornerRadius)
        assertEquals(Color(0x1AFFFFFF), surfaces.acrylicBorderColor)
        assertEquals(1.dp, surfaces.acrylicBorderStroke)
    }

    @Test
    fun statesVisualsVerification() {
        assertEquals(Color(0xFF2C3B45), states.active.backgroundColor)
        assertEquals(colors.accent, states.active.contentColor)
        assertEquals(Color(0xFF243140), states.inactive.backgroundColor)
        assertEquals(Color(0xFFA9BAB6), states.inactive.contentColor)
        assertEquals(0.4f, states.disabled.alpha, 0.001f)
    }

    /** One token per value: two names for one colour is how the sprawl started. */
    @Test
    fun noTwoColourTokensShareAValue() {
        val tokens = AstrionColors::class.java.declaredFields
            .filter { it.type == Long::class.javaPrimitiveType && !it.isSynthetic }
            .onEach { it.isAccessible = true }
            .associate { it.name to it.getLong(colors) }
        assertTrue("expected the colour tokens, found ${tokens.size}", tokens.size > 40)
        val dupes = tokens.entries.groupBy({ it.value }, { it.key }).values.filter { it.size > 1 }
        assertTrue("tokens with the same value: $dupes", dupes.isEmpty())
    }

    /** Porter's Paints: Baby Doll carries attention, Hailstorm the accents. */
    @Test
    fun paletteRoles() {
        assertEquals(Color(0xFFE6BCB6), colors.blush)
        assertEquals(Color(0xFF4F726D), colors.accentStrong)
        assertEquals(Color(0xFF2F3E4C), colors.alarmTop)
        // Lights that are on stay warm amber, the one colour off the card.
        assertEquals(Color(0xFFFFC24B), colors.lightOn)
    }

    @Test
    fun typeSizesAscend() {
        val s = typography.sizes
        val scale = listOf(
            s.micro, s.label, s.small, s.body, s.bodyLarge, s.emphasis, s.title,
            s.titleLarge, s.heading, s.headingLarge, s.displayXs, s.displaySm, s.displayMd, s.displayLg,
            s.displayXl, s.readout, s.readoutLarge, s.alarmClock, s.restClock, s.clock,
        ).map { it.value }
        assertEquals(scale.sorted(), scale)
        assertEquals(scale.distinct(), scale)
    }

    /** 12 sp floor: only the two-letter "ON" badge goes below it. */
    @Test
    fun noTextBelowTheFloor() {
        val s = typography.sizes
        val readable = listOf(s.label, s.small, s.body, s.bodyLarge, s.emphasis, s.title)
        readable.forEach { assertTrue("$it below the floor", it.value >= MIN_TEXT.value) }
        val styles = listOf(
            typography.label, typography.body, typography.title, typography.bodySmall,
            typography.labelLarge, typography.labelMedium, typography.labelSmall,
        )
        styles.forEach { assertTrue("${it.fontSize} below the floor", it.fontSize.value >= MIN_TEXT.value) }
    }

    @Test
    fun wcagContrastVerification() {
        fun channelLuminance(c: Float): Double =
            if (c <= 0.03928f) c / 12.92 else Math.pow(((c + 0.055) / 1.055), 2.4)

        fun relativeLuminance(color: Color): Double =
            0.2126 * channelLuminance(color.red) +
            0.7152 * channelLuminance(color.green) +
            0.0722 * channelLuminance(color.blue)

        fun contrastRatio(fg: Color, bg: Color): Double {
            val l1 = relativeLuminance(fg)
            val l2 = relativeLuminance(bg)
            val brighter = maxOf(l1, l2)
            val darker = minOf(l1, l2)
            return (brighter + 0.05) / (darker + 0.05)
        }

        val onSurfaceRatio = contrastRatio(colors.onSurface, colors.surfacePrimary)
        assertTrue("onSurface contrast $onSurfaceRatio should be >= 4.5", onSurfaceRatio >= 4.5)

        val mutedRatio = contrastRatio(colors.onSurfaceMuted, colors.surfacePrimary)
        assertTrue("onSurfaceMuted contrast $mutedRatio should be >= 4.5", mutedRatio >= 4.5)

        val accentRatio = contrastRatio(colors.accent, colors.surfacePrimary)
        assertTrue("accent contrast $accentRatio should be >= 4.5", accentRatio >= 4.5)

        val blushRatio = contrastRatio(colors.blush, colors.surface)
        assertTrue("blush contrast $blushRatio should be >= 4.5", blushRatio >= 4.5)

        val onBlushRatio = contrastRatio(colors.onBlush, colors.blush)
        assertTrue("onBlush contrast $onBlushRatio should be >= 4.5", onBlushRatio >= 4.5)
    }
}
