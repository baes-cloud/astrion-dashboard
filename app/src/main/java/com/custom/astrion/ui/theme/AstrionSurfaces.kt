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
    // ---- Alpha & Glassmorphism ----------------------------------------------
    val cardBackgroundAlpha: Float = 1.0f,
    val cardBackgroundAltAlpha: Float = 1.0f,
    val unavailableAlpha: Float = 0.4f,
    val scrimAlpha: Float = 0.80f,
    val acrylicBorderStroke: Dp = 1.dp,
    val acrylicBorderColor: Color = palette.hairline,        // 10% white subtle acrylic border
    val acrylicBorderStrongColor: Color = palette.hairlineStrong,  // 20% white border for active buttons
    val acrylicOverlayBg: Color = palette.glass,          // 8% white glassmorphism surface fill

    // ---- Corner Radii -------------------------------------------------------
    val cardCornerRadius: Dp = 18.dp,                         // Standard card container
    val cardCornerRadiusSmall: Dp = 14.dp,                    // Action grid buttons, inner tiles
    val pillCornerRadius: Dp = 13.dp,                         // Scene pills
    val dialogCornerRadius: Dp = 24.dp,                       // Modal sheets, popups
    val controlCornerRadius: Dp = 12.dp,                      // Control chips, round action buttons
    val chipCornerRadius: Dp = 10.dp,                         // Connection pill, mini status badges
    val fullCornerRadius: Dp = 999.dp,                        // Circular controls

    // ---- Pre-built Shapes ---------------------------------------------------
    val cardShape: RoundedCornerShape = RoundedCornerShape(18.dp),
    val cardSmallShape: RoundedCornerShape = RoundedCornerShape(14.dp),
    val pillShape: RoundedCornerShape = RoundedCornerShape(13.dp),
    val dialogShape: RoundedCornerShape = RoundedCornerShape(24.dp),
    val controlShape: RoundedCornerShape = RoundedCornerShape(12.dp),
    val chipShape: RoundedCornerShape = RoundedCornerShape(10.dp),
    val circleShape: Shape = CircleShape,
)
