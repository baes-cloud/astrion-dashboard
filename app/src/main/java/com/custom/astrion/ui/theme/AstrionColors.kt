package com.custom.astrion.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Bæo v3.0 — Matte Tonal Architecture (Zero-Glass, Borderless Porter's Paints).
 *
 * Surfaces separate purely through velvety matte tonal elevation locked to
 * Yacht Race Slate (209°-210°) and negative space — zero harsh 1dp borders,
 * zero glassmorphism, zero specular rims. Every ink × surface pairing meets
 * WCAG 2.1 AA (>= 4.5:1) verified by [AstrionDesignSystemTest].
 *
 * One unique ARGB value per token: never duplicate hex values across fields.
 */
@Immutable
data class AstrionColors(
    // ---- matte neutral ladder (darkest to lightest, 209°-210° hue) ----------
    /** Tier 0: Deep artwork mat and modal void. */
    val void: Color = Color(0xFF0D1217),
    /** Sunken well inside bottom sheets (vertical fader bed). */
    val surfaceSunken: Color = Color(0xFF11171E),
    /** Tier 1: Borderless recessed troughs (segmented tracks, blind wells). */
    val surfacePrimary: Color = Color(0xFF141B22),
    /** Seamless canvas match for pinned top telemetry (floats directly on canvas). */
    val pinnedTopBg: Color = Color(0xFF171F28),
    /** Seamless canvas match for pinned bottom band / overlays. */
    val pinnedBottomBg: Color = Color(0xFF151D25),
    /** Tier 2: Calm matte page ground. */
    val background: Color = Color(0xFF171F27),
    /** Slider & fader recessed trough (distinct token from surfacePrimary). */
    val surfaceCard: Color = Color(0xFF141C23),
    /** Tier 3: Canonical borderless matte card fill across all 4 pages. */
    val surface: Color = Color(0xFF222B35),
    /** Subtle alternate matte card fill (secondary tiles, floorplan frame). */
    val surfaceVariant: Color = Color(0xFF25303B),
    /** Tier 4: Elevated matte surface (Volume OSD, active tab pill, sheets). */
    val surfaceElevated: Color = Color(0xFF28333F),
    /** Quiet inset pill / on-demand drawer trigger bar. */
    val controlSunken: Color = Color(0xFF1C242D),
    /** Resting borderless matte pills & inner tiles. */
    val raised: Color = Color(0xFF2B3642),
    /** Tier 5: Resting matte controls (steppers, blind rockers, Door pill). */
    val controlBg: Color = Color(0xFF2D3844),
    /** Pressed chrome & WCAG 1.4.11 passing inactive PageRule (3.15:1 on canvas). */
    val controlRaised: Color = Color(0xFF5B6E7D),

    // ---- zero-failure ink system (WCAG 2.1 AA/AAA on all matte tiers) -------
    val onSurface: Color = Color(0xFFF0F4F2),
    val textOnControl: Color = Color(0xFFE4ECE9),
    val textSoft: Color = Color(0xFFCDD8D5),
    val onSurfaceVariant: Color = Color(0xFFBCCAC6),
    val onSurfaceMuted: Color = Color(0xFFB2C1BD),
    /** Muted metadata, captions, solid footer wordmark (>= 4.68:1 on controlBg). */
    val textMuted: Color = Color(0xFF9DB0AC),
    val textInverse: Color = Color(0xFF12161A),
    val onPrimary: Color = Color(0xFFF2F6F4),

    // ---- Porter's Paints accents: Hailstorm & Gunmetal ----------------------
    /** Hailstorm: solid matte Play/Pause circle, active icons, PageRule ON. */
    val accent: Color = Color(0xFF88BEB6),
    /** Crisp dark ink inside solid matte Hailstorm controls (8.65:1 AAA). */
    val onAccent: Color = Color(0xFF141C22),
    /** Brighter Hailstorm tint for highlights and progress fills. */
    val accentBright: Color = Color(0xFFA9D2CB),
    /** Smooth Gunmetal matte fill: Joined, active chips, Linked/Player faders (6.62:1). */
    val accentStrong: Color = Color(0xFF3B5F59),
    /** Smooth quiet Gunmetal fill for unlinked Zone faders (8.55:1 AAA). */
    val accentFillQuiet: Color = Color(0xFF2C4844),
    /** Muted Gunmetal for secondary indicators. */
    val accentMuted: Color = Color(0xFF4D6B66),
    /** Velvety Hailstorm wash: BÆOLINK hero, linked zone row, Aircon Cool. */
    val accentContainer: Color = Color(0xFF1F3433),

    // ---- warm matte ochre & dusty-rose semantic pills (Pixel 10a DNA) -------
    /** Matte warm ochre pill fill for active ambient toys (Mirror Ball, Neon, TV Lights). */
    val ochreSurface: Color = Color(0xFF564D31),
    /** Warm ochre ink on ochreSurface (5.92:1 AA). */
    val ochreInk: Color = Color(0xFFF2CE63),
    /** Baby Doll: attention, next alarm, blind hem, muted label. */
    val blush: Color = Color(0xFFE6BCB6),
    val onBlush: Color = Color(0xFF3E221E),
    /** Matte dusty-rose fill: Door Held open, Automations Paused, Blinds Shut (6.10:1). */
    val blushContainer: Color = Color(0xFF473131),
    /** Velvety Terracotta-Rose wash: Aircon Heating hero card. */
    val warmSurface: Color = Color(0xFF382727),
    /** Heating accent ink & scale indicator. */
    val warmHeat: Color = Color(0xFFE8A682),
    /** Blackout blind (Sofa) visualizer & smooth fader fill (7.51:1). */
    val blindSolidFill: Color = Color(0xFF664642),
    /** Sheer blind visualizer & smooth fader fill (8.98:1 AAA). */
    val blindSheerFill: Color = Color(0xFF4D3F43),

    // ---- physical remote colour dots (passive hardware key legend) ----------
    val keyRed: Color = Color(0xFFE0564F),
    val keyGreen: Color = Color(0xFF52B26B),
    val keyBlue: Color = Color(0xFF4F86D9),
    val keyYellow: Color = Color(0xFFE8C34A),

    // ---- lights that are on (solid matte butter-amber discs) ----------------
    /** Solid matte lit floorplan disc fill (9.85:1 with dark icon). */
    val lightOn: Color = Color(0xFFE5C255),
    val onLightOn: Color = Color(0xFF191D20),
    val lightOnBright: Color = Color(0xFFFFD37A),
    val lightWarmWhite: Color = Color(0xFFFFD9A0),

    // ---- errors (red is for faults only) ------------------------------------
    val danger: Color = Color(0xFFF49393),
    val dangerSoft: Color = Color(0xFFE87E7E),
    val dangerBg: Color = Color(0xFF3B2326),
    val onDanger: Color = Color(0xFF260D10),

    /** Unavailable label ink: always rendered at 1.0f alpha (5.40:1 on surface). */
    val unavailable: Color = Color(0xFF90A1AB),
    val unavailableAlpha: Float = 0.55f,

    // ---- subtle overlays & scrims (zero harsh borders) ----------------------
    val hairline: Color = Color(0x0DFFFFFF),
    val hairlineStrong: Color = Color(0x1FFFFFFF),
    val glass: Color = Color(0x14FFFFFF),
    val divider: Color = Color(0x262D3844),
    val scrim: Color = Color(0xCC0B1015),
    val scrimDark: Color = Color(0xF00A0F14),

    // ---- alarm screen: matte Yacht Race into night --------------------------
    val alarmTop: Color = Color(0xFF2A3744),
    val alarmMid: Color = Color(0xFF1C2733),
    val alarmBottom: Color = Color(0xFF131B24),

    // ---- night screensaver --------------------------------------------------
    val dayClock: Color = Color(0xFFDDE7E3),
    val nightFace: Color = Color(0xFF0B0D0F),
    val nightInk: Color = Color(0xFFC89C96),
    val nightInk2: Color = Color(0xFFA07A74),
    val nightTrack: Color = Color(0xFF1A1D21),
)
