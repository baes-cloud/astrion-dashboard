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

/** Control states, built from [AstrionColors] so they can't drift from it. */
@Immutable
data class AstrionStates(
    val active: StateVisuals = StateVisuals(
        backgroundColor = palette.controlSunken,
        contentColor = palette.accent,
        borderColor = palette.hairline,
        borderStroke = 1.dp,
    ),
    val inactive: StateVisuals = StateVisuals(
        backgroundColor = palette.surface,
        contentColor = palette.onSurfaceMuted,
    ),
    val pressed: StateVisuals = StateVisuals(
        backgroundColor = palette.pinnedTopBg,
        contentColor = palette.onSurface,
        alpha = 0.95f,
    ),
    val disabled: StateVisuals = StateVisuals(
        backgroundColor = palette.surfaceCard,
        contentColor = palette.unavailable,
        alpha = palette.unavailableAlpha,
    ),
)
