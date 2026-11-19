package com.custom.astrion.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class AstrionDesignSystemTest {

    private val colors = AstrionDesignSystem.defaultColors
    private val typography = AstrionDesignSystem.defaultTypography
    private val elevation = AstrionDesignSystem.defaultElevation
    private val surfaces = AstrionDesignSystem.defaultSurfaces
    private val states = AstrionDesignSystem.defaultStates

    @Test
    fun surfaceTokensMatchMatteV3Oracle() {
        assertEquals(Color(0xFF141B22), colors.surfacePrimary)
        assertEquals(Color(0xFF171F27), colors.background)
        assertEquals(Color(0xFF141C23), colors.surfaceCard)
        assertEquals(Color(0xFF222B35), colors.surface)
        assertEquals(Color(0xFF28333F), colors.surfaceElevated)
        assertEquals(Color(0xFF11171E), colors.surfaceSunken)
        assertEquals(Color(0xFF88BEB6), colors.accent)
        assertEquals(Color(0xFFF0F4F2), colors.onSurface)
        assertEquals(Color(0xFFB2C1BD), colors.onSurfaceMuted)
        assertEquals(0.55f, colors.unavailableAlpha, 0.001f)
    }

    @Test
    fun typographyRolesMatchSpecification() {
        assertEquals(34.sp, typography.display.fontSize)
        assertEquals(40.sp, typography.display.lineHeight)

        assertEquals(16.sp, typography.title.fontSize)
        assertEquals(20.sp, typography.title.lineHeight)

        assertEquals(14.sp, typography.body.fontSize)
        assertEquals(18.sp, typography.body.lineHeight)

        assertEquals(12.sp, typography.label.fontSize)
        assertEquals(16.sp, typography.label.lineHeight)

        // Rule of Two: readoutFont uses Manrope (bodyFont), Antonio retired.
        assertEquals(typography.bodyFont, typography.readoutFont)
    }

    @Test
    fun elevationTokensMatchSpecification() {
        assertEquals(0.dp, elevation.flat)
        assertEquals(2.dp, elevation.raised)
        assertEquals(8.dp, elevation.floating)
        assertEquals(5.dp, elevation.tactileLip)
    }

    @Test
    fun surfacesTokensAreBorderlessMatte() {
        assertEquals(18.dp, surfaces.cardCornerRadius)
        assertEquals(14.dp, surfaces.cardCornerRadiusSmall)
        assertEquals(22.dp, surfaces.dialogCornerRadius)
        assertEquals(0.dp, surfaces.acrylicBorderStroke)
        assertEquals(Color.Transparent, surfaces.acrylicBorderColor)
    }

    @Test
    fun statesVisualsVerification() {
        assertEquals(colors.accentContainer, states.active.backgroundColor)
        assertEquals(colors.accent, states.active.contentColor)
        assertEquals(0.dp, states.active.borderStroke)
        assertEquals(colors.surface, states.inactive.backgroundColor)
        assertEquals(colors.onSurfaceMuted, states.inactive.contentColor)
        assertEquals(colors.unavailableAlpha, states.disabled.alpha, 0.001f)
    }

    /** One token per value: two names for one colour is how the sprawl started. */
    @Test
    fun noTwoColourTokensShareAValue() {
        val tokens = AstrionColors::class.java.declaredFields
            .filter { it.type == Long::class.javaPrimitiveType && !it.isSynthetic }
            .onEach { it.isAccessible = true }
            .associate { it.name to it.getLong(colors) }
        assertTrue("expected the colour tokens, found ${tokens.size}", tokens.size > 45)
        val dupes = tokens.entries.groupBy({ it.value }, { it.key }).values.filter { it.size > 1 }
        assertTrue("tokens with the same value: $dupes", dupes.isEmpty())
    }

    /** Porter's Paints matte palette roles. */
    @Test
    fun paletteRoles() {
        assertEquals(Color(0xFFE6BCB6), colors.blush)
        assertEquals(Color(0xFF3B5F59), colors.accentStrong)
        assertEquals(Color(0xFF564D31), colors.ochreSurface)
        assertEquals(Color(0xFF473131), colors.blushContainer)
        assertEquals(Color(0xFFE5C255), colors.lightOn)
    }

    @Test
    fun typeSizesAscend() {
        val s = typography.sizes
        val scale = listOf(
            s.micro, s.label, s.body, s.emphasis, s.titleLarge, s.displayXs, s.displayMd,
            s.displayXl, s.readout, s.readoutLarge, s.clock, s.alarmClock, s.restClock,
        ).map { it.value }
        assertEquals(scale.sorted(), scale)
        assertEquals(scale.distinct(), scale)
    }

    /** 12 sp floor: only micro status badges ("ON", "NOW") go below it. */
    @Test
    fun noTextBelowTheFloor() {
        val s = typography.sizes
        val readable = listOf(s.label, s.body, s.emphasis)
        readable.forEach { assertTrue("$it below the floor", it.value >= MIN_TEXT.value) }
        val styles = listOf(
            typography.label, typography.body, typography.title, typography.bodySmall,
            typography.labelLarge, typography.labelMedium, typography.labelSmall,
        )
        styles.forEach { assertTrue("${it.fontSize} below the floor", it.fontSize.value >= MIN_TEXT.value) }
    }

    @Test
    fun wcag21FullMatrixContrastVerification() {
        fun channelLuminance(c: Float): Double =
            if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

        fun relativeLuminance(color: Color): Double =
            0.2126 * channelLuminance(color.red) +
            0.7152 * channelLuminance(color.green) +
            0.0722 * channelLuminance(color.blue)

        fun contrastRatio(fg: Color, bg: Color): Double {
            val l1 = relativeLuminance(fg)
            val l2 = relativeLuminance(bg)
            return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
        }

        fun assertAa(name: String, fg: Color, bg: Color, min: Double = 4.5) {
            val r = contrastRatio(fg, bg)
            assertTrue("$name contrast ${"%.2f".format(r)} < $min", r >= min)
        }

        // All text tokens across all matte neutral tiers up to controlBg
        val tiers = listOf(
            "canvas" to colors.background,
            "surface" to colors.surface,
            "surfaceElevated" to colors.surfaceElevated,
            "raised" to colors.raised,
            "controlBg" to colors.controlBg,
        )
        for ((tierName, bg) in tiers) {
            assertAa("onSurface on $tierName", colors.onSurface, bg)
            assertAa("onSurfaceMuted on $tierName", colors.onSurfaceMuted, bg)
            assertAa("textMuted on $tierName", colors.textMuted, bg)
            assertAa("accent on $tierName", colors.accent, bg)
            assertAa("blush on $tierName", colors.blush, bg)
            assertAa("danger on $tierName", colors.danger, bg)
        }

        // Smooth matte fader fills & semantic pills
        assertAa("onPrimary on accentStrong (Linked/Player fader)", colors.onPrimary, colors.accentStrong)
        assertAa("onSurface on accentFillQuiet (Unlinked fader)", colors.onSurface, colors.accentFillQuiet)
        assertAa("onSurface on blindSolidFill", colors.onSurface, colors.blindSolidFill)
        assertAa("onSurface on blindSheerFill", colors.onSurface, colors.blindSheerFill)
        assertAa("onAccent on accent (Play/Pause)", colors.onAccent, colors.accent)
        assertAa("ochreInk on ochreSurface", colors.ochreInk, colors.ochreSurface)
        assertAa("blush on blushContainer", colors.blush, colors.blushContainer)
        assertAa("onLightOn on lightOn (Floorplan lit disc)", colors.onLightOn, colors.lightOn)
        assertAa("onDanger on danger", colors.onDanger, colors.danger)
        assertAa("danger on dangerBg", colors.danger, colors.dangerBg)
        assertAa("unavailable on surface (1.0f alpha)", colors.unavailable, colors.surface)

        // WCAG 1.4.11 non-text UI contrast (>= 3.0:1) for inactive PageRule
        assertAa("PageRule off (controlRaised) on canvas", colors.controlRaised, colors.background, min = 3.0)
    }
}
