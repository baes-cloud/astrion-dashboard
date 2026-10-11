package com.custom.astrion.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class AstrionElevation(
    val flat: Dp = 0.dp,                               // Resting background elevation
    val cardDefault: Dp = 0.dp,                        // Flat dark surface elevation
    val cardRaised: Dp = 2.dp,                         // Raised card / button face
    val cardPressed: Dp = 0.dp,                        // Depressed button face
    val raised: Dp = 2.dp,                             // Matches DesignSystemModel.ELEVATION["raised"]
    val floating: Dp = 8.dp,                           // Matches DesignSystemModel.ELEVATION["floating"]
    val dialog: Dp = 8.dp,                             // Modal popups and sheets
    val tactileLip: Dp = 5.dp,                         // 3D tactile sink depth
    val pillLip: Dp = 3.dp,                            // Subtle pill lip depth
)
