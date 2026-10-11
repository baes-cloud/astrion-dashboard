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

@Immutable
data class AstrionStates(
    val active: StateVisuals = StateVisuals(
        backgroundColor = Color(0xFF2C3B45),
        contentColor = Color(0xFFFFC24B),
        borderColor = Color(0x1AFFFFFF),
        borderStroke = 1.dp,
        alpha = 1.0f,
    ),
    val inactive: StateVisuals = StateVisuals(
        backgroundColor = Color(0xFF243140),
        contentColor = Color(0xFFA9BAB6),
        alpha = 1.0f,
    ),
    val pressed: StateVisuals = StateVisuals(
        backgroundColor = Color(0xFF151D25),
        contentColor = Color(0xFFEEF2EF),
        alpha = 0.95f,
    ),
    val disabled: StateVisuals = StateVisuals(
        backgroundColor = Color(0xFF1C2630),
        contentColor = Color(0xFF7B8C96),
        alpha = 0.4f,
    ),
)
