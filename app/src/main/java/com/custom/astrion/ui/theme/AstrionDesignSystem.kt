package com.custom.astrion.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.custom.astrion.ui.StrongHaptics

val LocalAstrionColors = staticCompositionLocalOf { AstrionColors() }
val LocalAstrionTypography = staticCompositionLocalOf { defaultAstrionTypography() }
val LocalAstrionElevation = staticCompositionLocalOf { AstrionElevation() }
val LocalAstrionSurfaces = staticCompositionLocalOf { AstrionSurfaces() }
val LocalAstrionStates = staticCompositionLocalOf { AstrionStates() }

object AstrionDesignSystem {
    val defaultColors: AstrionColors = AstrionColors()
    val defaultTypography: AstrionTypography = defaultAstrionTypography()
    val defaultElevation: AstrionElevation = AstrionElevation()
    val defaultSurfaces: AstrionSurfaces = AstrionSurfaces()
    val defaultStates: AstrionStates = AstrionStates()

    val colors: AstrionColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAstrionColors.current

    val typography: AstrionTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalAstrionTypography.current

    val elevation: AstrionElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalAstrionElevation.current

    val surfaces: AstrionSurfaces
        @Composable
        @ReadOnlyComposable
        get() = LocalAstrionSurfaces.current

    val states: AstrionStates
        @Composable
        @ReadOnlyComposable
        get() = LocalAstrionStates.current
}

@Composable
fun AstrionDesignSystemTheme(
    colors: AstrionColors = AstrionColors(),
    typography: AstrionTypography = defaultAstrionTypography(),
    elevation: AstrionElevation = AstrionElevation(),
    surfaces: AstrionSurfaces = AstrionSurfaces(),
    states: AstrionStates = AstrionStates(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val haptics = remember(context) { StrongHaptics(context) }
    val baseM3Typography = remember { Typography() }

    CompositionLocalProvider(
        LocalAstrionColors provides colors,
        LocalAstrionTypography provides typography,
        LocalAstrionElevation provides elevation,
        LocalAstrionSurfaces provides surfaces,
        LocalAstrionStates provides states,
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = colors.accentStrong,
                onPrimary = colors.onPrimary,
                secondary = colors.accentSage,
                background = colors.background,
                onBackground = colors.onBackground,
                surface = colors.surface,
                onSurface = colors.onSurface,
                surfaceVariant = colors.controlBg,
                onSurfaceVariant = colors.onSurfaceMuted,
                surfaceContainer = colors.surfaceVariant,
                outline = colors.outline,
                error = colors.danger,
            ),
            typography = baseM3Typography.copy(
                displayLarge = typography.displayLarge,
                displayMedium = typography.displayMedium,
                displaySmall = typography.displaySmall,
                titleLarge = typography.titleLarge,
                titleMedium = typography.titleMedium,
                titleSmall = typography.titleSmall,
                bodyLarge = typography.bodyLarge,
                bodyMedium = typography.bodyMedium,
                bodySmall = typography.bodySmall,
                labelLarge = typography.labelLarge,
                labelMedium = typography.labelMedium,
                labelSmall = typography.labelSmall,
            ),
            shapes = Shapes(extraSmall = surfaces.controlShape),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides colors.onSurface,
                LocalTextStyle provides androidx.compose.ui.text.TextStyle.Default.copy(fontFamily = typography.bodyFont),
                LocalHapticFeedback provides haptics,
                content = content,
            )
        }
    }
}

@Composable
fun AstrionTheme(
    colors: AstrionColors = AstrionColors(),
    typography: AstrionTypography = defaultAstrionTypography(),
    elevation: AstrionElevation = AstrionElevation(),
    surfaces: AstrionSurfaces = AstrionSurfaces(),
    states: AstrionStates = AstrionStates(),
    content: @Composable () -> Unit,
) {
    AstrionDesignSystemTheme(
        colors = colors,
        typography = typography,
        elevation = elevation,
        surfaces = surfaces,
        states = states,
        content = content,
    )
}

