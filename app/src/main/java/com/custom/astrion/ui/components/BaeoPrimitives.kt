package com.custom.astrion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.MIN_TOUCH
import com.custom.astrion.ui.dimIfUnavailable
import com.custom.astrion.ui.tap

/**
 * Pure typographic uppercase section label (zero borders, zero cluttered lines).
 */
@Composable
fun BaeoSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AstrionTheme.textMuted,
) {
    Text(
        text = text.uppercase(),
        color = color,
        fontFamily = AstrionTheme.bodyFont,
        fontSize = AstrionTheme.size.label,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.13.em,
        maxLines = 1,
        modifier = modifier.padding(start = 2.dp),
    )
}

/**
 * Borderless recessed matte segmented control housing (`#141B22` trough with
 * `3dp` padding and concentric `11dp` active pill). Shared across Media tabs
 * and Climate Mode / Fan Speed rows.
 */
@Composable
fun BaeoSegmentedBar(
    modifier: Modifier = Modifier,
    trackColor: Color = AstrionTheme.colors.surfacePrimary,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(trackColor)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun RowScope.BaeoSegmentItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 42.dp,
    selectedBg: Color = AstrionTheme.accentStrong,
    selectedInk: Color = AstrionTheme.colors.onPrimary,
) {
    Row(
        modifier = modifier
            .weight(1f)
            .height(height)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) selectedBg else Color.Transparent)
            .tap(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) selectedInk else AstrionTheme.textSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            color = if (selected) selectedInk else AstrionTheme.textSecondary,
            fontSize = AstrionTheme.size.label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * Accessible matte toggle switch with a full 48dp hit target and [Role.Switch].
 */
@Composable
fun BaeoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .size(width = MIN_TOUCH, height = MIN_TOUCH)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCheckedChange(it)
                },
            ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 24.dp)
                .dimIfUnavailable(!enabled)
                .clip(CircleShape)
                .background(if (checked) AstrionTheme.accent else AstrionTheme.controlBg)
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (checked) AstrionTheme.onAccent else AstrionTheme.textSecondary),
            )
        }
    }
}
