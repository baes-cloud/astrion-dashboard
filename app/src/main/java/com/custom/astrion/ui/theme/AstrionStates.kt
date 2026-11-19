package com.custom.astrion.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class StateVisuals(
    val backgroundColor: Color,
    val contentColor: Color,
    val borderColor: Color = Color.Transparent,
    val borderStroke: Dp = 0.dp,
    val alpha: Float = 1.0f,
)

private val palette = AstrionColors()

/** Borderless matte control states derived directly from [AstrionColors]. */
@Immutable
data class AstrionStates(
    val active: StateVisuals = StateVisuals(
        backgroundColor = palette.accentContainer,
        contentColor = palette.accent,
        borderColor = Color.Transparent,
        borderStroke = 0.dp,
    ),
    val inactive: StateVisuals = StateVisuals(
        backgroundColor = palette.surface,
        contentColor = palette.onSurfaceMuted,
    ),
    val pressed: StateVisuals = StateVisuals(
        backgroundColor = palette.surfaceElevated,
        contentColor = palette.onSurface,
        alpha = 0.96f,
    ),
    val disabled: StateVisuals = StateVisuals(
        backgroundColor = palette.surfaceCard,
        contentColor = palette.unavailable,
        alpha = palette.unavailableAlpha,
    ),
)
