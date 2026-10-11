package com.custom.astrion.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class AstrionColors(
    // ---- E2E Contract Tokens (DesignSystemModel.SURFACES) -------------------
    val surfacePrimary: Color = Color(0xFF151B21),     // Deepest background (Dark teal-grey)
    val surfaceCard: Color = Color(0xFF1C2630),        // Base card surface / slider track
    val surfaceElevated: Color = Color(0xFF243140),    // Raised card / dialog surface
    val surfaceSunken: Color = Color(0xFF0F1318),      // Sunken 3D button lip background
    val accent: Color = Color(0xFFFFC24B),             // Active gold-amber accent (E2E contract & active state)
    val onSurface: Color = Color(0xFFEEF2EF),          // High contrast primary text (Light sage, 15.4:1 contrast)
    val onSurfaceMuted: Color = Color(0xFFA9BAB6),     // Muted secondary text (Hailstorm grey, 8.5:1 contrast)
    val unavailableAlpha: Float = 0.4f,                // Dimming opacity for disconnected entities

    // ---- Material 3 & Smart Display Semantic Extensions ---------------------
    val primary: Color = Color(0xFF4F726D),            // Gunmetal Grey: filled buttons & chips
    val onPrimary: Color = Color.White,
    val background: Color = Color(0xFF1B242D),         // Yacht Race base page background
    val onBackground: Color = Color(0xFFEEF2EF),
    val surface: Color = Color(0xFF243140),            // Default card surface fill
    val surfaceVariant: Color = Color(0xFF283646),     // Alternate card fill (secondary cards, tiles)
    val onSurfaceVariant: Color = Color(0xFFAEBFBB),   // Section badges, secondary title labels
    val surfaceSunkenControl: Color = Color(0xFF2C3B45), // Recessed lip under active scene tiles
    val controlSunken: Color = Color(0xFF2C3B45),      // Recessed control, e.g. the lip under a scene tile
    val pinnedTopBg: Color = Color(0xFF151D25),        // Pinned header band
    val pinnedBottomBg: Color = Color(0xFF182129),     // Pinned footer band
    val raised: Color = Color(0xFF34454F),             // Raised element inside cards (grid buttons)
    val controlBg: Color = Color(0xFF3A4F57),          // Control chrome: round buttons, chips
    val trackBg: Color = Color(0xFF1C2630),            // Slider / progress track

    // ---- Content Text Inks --------------------------------------------------
    val textPrimary: Color = Color(0xFFEEF2EF),        // High-contrast primary text
    val textSecondary: Color = Color(0xFFA9BAB6),      // Secondary labels, captions
    val textOnControl: Color = Color(0xFFD9E3E0),      // Icon/text on control chrome buttons
    val textMuted: Color = Color(0xFF718583),          // Faint metadata, timestamps
    val textInverse: Color = Color(0xFF141414),        // Dark text on high-luminance tiles

    // ---- Accent & State Palettes --------------------------------------------
    val accentSage: Color = Color(0xFF8CBDB5),         // Hailstorm: lifted text, icons, progress
    val accentStrong: Color = Color(0xFF4F726D),       // Gunmetal: selected state
    val onWarm: Color = Color(0xFF241A00),             // Dark text on active amber
    val blush: Color = Color(0xFFE6BCB6),              // Baby Doll warm accent: attention, alarms
    val onBlush: Color = Color(0xFF4A2A26),            // Dark text on blush
    val danger: Color = Color(0xFFE37B7B),             // Danger / error alert
    val dangerSoft: Color = Color(0xFFE06767),         // Soft red toggle / disconnected state
    val dangerBg: Color = Color(0xFF3A2E2E),           // Danger container background
    val onDanger: Color = Color(0xFF241012),           // Dark text on red banner
    val success: Color = Color(0xFF3DD68C),            // High temp / battery charging green
    val successSoft: Color = Color(0xFF8CBDB5),        // Good / normal status
    val warning: Color = Color(0xFFFFB347),            // Warning amber
    val warningContainer: Color = Color(0xFF4A3B1E),   // Config warning banner background
    val unavailable: Color = Color(0xFF7B8C96),        // Desaturated slate for dead/unknown entities
    val outline: Color = Color(0xFF3A4F57),            // Border outline
    val divider: Color = Color(0x333A4F57),            // 20% opacity hairline divider
    val scrim: Color = Color(0xCC0B1015),              // 80% opacity dark overlay scrim
    val scrimDark: Color = Color(0xF00A0F14),          // 94% opacity deep scrim for emergency alarms

    // ---- Docked Screensaver / Night Palette ---------------------------------
    val nightFace: Color = Color(0xFF0C0E10),          // Docked night face ground
    val nightInk: Color = Color(0xFFC29690),           // Dimmed Baby Doll rose clock and title
    val nightInk2: Color = Color(0xFF9C7570),          // Secondary night meta, progress, date
    val nightTrack: Color = Color(0xFF1A1D21),         // Night progress track
    val dayClock: Color = Color(0xFFDDE7E3),           // Day screensaver clock ink
    val dayInk: Color = Color(0xFFA9BAB6),             // Day screensaver secondary ink
    val dayFaint: Color = Color(0xFF718583),           // Day screensaver faint text
    val dayAccent: Color = Color(0xFFE8B25A),          // Day screensaver accent
    val dayName: Color = Color(0xFFE9EDF2),            // Day screensaver device name
)
