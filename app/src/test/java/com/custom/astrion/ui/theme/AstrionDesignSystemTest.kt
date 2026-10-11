package com.custom.astrion.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.ui.AstrionTheme
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
        assertEquals(Color(0xFF243140), colors.surfaceElevated)
        assertEquals(Color(0xFF0F1318), colors.surfaceSunken)
        assertEquals(Color(0xFFFFC24B), colors.accent)
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
        assertEquals(Color(0xFFFFC24B), states.active.contentColor)
        assertEquals(Color(0xFF243140), states.inactive.backgroundColor)
        assertEquals(Color(0xFFA9BAB6), states.inactive.contentColor)
        assertEquals(0.4f, states.disabled.alpha, 0.001f)
    }

    @Test
    fun themeBridgeBackwardCompatibility() {
        assertEquals(colors.background, AstrionTheme.pageBg)
        assertEquals(colors.pinnedTopBg, AstrionTheme.pinnedTopBg)
        assertEquals(colors.surfaceElevated, AstrionTheme.cardBg)
        assertEquals(colors.onSurface, AstrionTheme.textPrimary)
        assertEquals(colors.onSurfaceMuted, AstrionTheme.textSecondary)
        assertEquals(colors.accentSage, AstrionTheme.accent)
        assertEquals(colors.accent, AstrionTheme.on)
        assertEquals(colors.onWarm, AstrionTheme.onBg)
        assertEquals(typography.displaySize, AstrionTheme.display)
        assertEquals(typography.titleSize, AstrionTheme.title)
        assertEquals(typography.bodySize, AstrionTheme.body)
        assertEquals(typography.labelSize, AstrionTheme.label)
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
    }
}
