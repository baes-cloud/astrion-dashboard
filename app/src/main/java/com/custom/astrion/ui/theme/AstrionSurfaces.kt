package com.custom.astrion.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val palette = AstrionColors()

@Immutable
data class AstrionSurfaces(
    // ---- Matte Tonal Elevation (Zero Glass / Zero Borders) ------------------
    val cardBackgroundAlpha: Float = 1.0f,
    val cardBackgroundAltAlpha: Float = 1.0f,
    val unavailableAlpha: Float = palette.unavailableAlpha,
    val scrimAlpha: Float = 0.80f,
    val acrylicBorderStroke: Dp = 0.dp,
    val acrylicBorderColor: Color = Color.Transparent,
    val acrylicBorderStrongColor: Color = Color.Transparent,
    val acrylicOverlayBg: Color = palette.surfaceElevated,

    // ---- Unified Corner Radii -----------------------------------------------
    val cardCornerRadius: Dp = 18.dp,                         // Canonical borderless card
    val cardCornerRadiusSmall: Dp = 14.dp,                    // Troughs, keys, inner tiles
    val pillCornerRadius: Dp = 14.dp,                         // Scene & quick action pills
    val dialogCornerRadius: Dp = 22.dp,                       // Bottom sheets, popups
    val controlCornerRadius: Dp = 12.dp,                      // Segmented inner pills, chips
    val chipCornerRadius: Dp = 10.dp,                         // Mini badges, artwork corners
    val fullCornerRadius: Dp = 999.dp,                        // Circular controls

    // ---- Pre-built Shapes ---------------------------------------------------
    val cardShape: RoundedCornerShape = RoundedCornerShape(18.dp),
    val cardSmallShape: RoundedCornerShape = RoundedCornerShape(14.dp),
    val pillShape: RoundedCornerShape = RoundedCornerShape(14.dp),
    val dialogShape: RoundedCornerShape = RoundedCornerShape(22.dp),
    val controlShape: RoundedCornerShape = RoundedCornerShape(12.dp),
    val chipShape: RoundedCornerShape = RoundedCornerShape(10.dp),
    val circleShape: Shape = CircleShape,
)
