package com.custom.astrion.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Every colour the app draws with. Porter's Paints, 2026-09: Yacht Race
 * #2F3E4C is the base the surfaces are built from, Hailstorm #6E9E97 and
 * Gunmetal Grey #4F726D carry accents and selected state, Baby Doll #E6BCB6
 * carries attention (warnings, the next alarm, mute). Off the card: red for
 * errors only, and warm amber for lights that are on.
 *
 * One token per value: read them through [com.custom.astrion.ui.AstrionTheme]
 * inside composition, never as literals.
 */
@Immutable
data class AstrionColors(
    // ---- surfaces, darkest to lightest --------------------------------------
    /** Near-black behind artwork and the lit-rooms shade. */
    val void: Color = Color(0xFF070809),
    val surfaceSunken: Color = Color(0xFF161F28),
    /** Deepest panel ground (dialogs' backdrop, the tab strip). */
    val surfacePrimary: Color = Color(0xFF151B21),
    val pinnedTopBg: Color = Color(0xFF151D25),
    val pinnedBottomBg: Color = Color(0xFF182129),
    /** Yacht Race, darkened: the page. */
    val background: Color = Color(0xFF1B242D),
    /** Slider and progress track. */
    val surfaceCard: Color = Color(0xFF1C2630),
    /** Default card fill. */
    val surface: Color = Color(0xFF243140),
    /** Alternate card fill (floorplan canvas, cover and switch tiles). */
    val surfaceVariant: Color = Color(0xFF283646),
    /** Recessed control, e.g. the lip under a scene tile. */
    val controlSunken: Color = Color(0xFF2C3B45),
    /** Raised element inside a card (grid buttons, scene tile faces). */
    val raised: Color = Color(0xFF34454F),
    /** Control chrome: small round buttons, chips, outlines. */
    val controlBg: Color = Color(0xFF3A4F57),
    /** Control chrome, pressed or highlighted. */
    val controlRaised: Color = Color(0xFF42565E),

    // ---- text ---------------------------------------------------------------
    val onSurface: Color = Color(0xFFEEF2EF),
    /** Between [onSurface] and [onSurfaceMuted]: secondary titles. */
    val textSoft: Color = Color(0xFFC3D0CD),
    val textOnControl: Color = Color(0xFFD9E3E0),
    /** Section badges, secondary title labels. */
    val onSurfaceVariant: Color = Color(0xFFAEBFBB),
    val onSurfaceMuted: Color = Color(0xFFA9BAB6),
    /** Faint metadata, timestamps. */
    val textMuted: Color = Color(0xFF718583),
    /** Dark text on light tiles. */
    val textInverse: Color = Color(0xFF141414),
    val onPrimary: Color = Color.White,

    // ---- accent: Hailstorm and Gunmetal -------------------------------------
    /** Hailstorm, lifted to read as text and icons on the dark cards. */
    val accent: Color = Color(0xFF8CBDB5),
    /** A lighter Hailstorm tint: highlights, progress ends. */
    val accentBright: Color = Color(0xFFA9D2CB),
    /** Gunmetal Grey: selected chips and filled buttons under white text. */
    val accentStrong: Color = Color(0xFF4F726D),
    /** Gunmetal, quietened: secondary selected state. */
    val accentMuted: Color = Color(0xFF506763),
    /** Dark Hailstorm ground (info alerts, "on" tiles). */
    val accentContainer: Color = Color(0xFF27403E),

    // ---- attention: Baby Doll -----------------------------------------------
    val blush: Color = Color(0xFFE6BCB6),
    val onBlush: Color = Color(0xFF4A2A26),
    /** Dark rose ground for warning banners and alerts. */
    val blushContainer: Color = Color(0xFF3E2F30),

    // ---- the remote's four coloured buttons, as the guide draws them --------
    // Off the card on purpose: they name physical buttons, not app state.
    val keyRed: Color = Color(0xFFE0564F),
    val keyGreen: Color = Color(0xFF52B26B),
    val keyBlue: Color = Color(0xFF4F86D9),
    val keyYellow: Color = Color(0xFFE8C34A),

    // ---- lights that are on (the one warm colour, by design) ----------------
    val lightOn: Color = Color(0xFFFFC24B),
    val onLightOn: Color = Color(0xFF241A00),
    /** A lit room's glow on the floorplan. */
    val lightOnBright: Color = Color(0xFFFFD37A),
    /** A bulb's colour when HA doesn't report one. */
    val lightWarmWhite: Color = Color(0xFFFFD9A0),

    // ---- weather glyphs: sky colours, soft so they never read as a light that is on
    val wxSun: Color = Color(0xFFF2CB7E),
    val wxSunFill: Color = Color(0x38F2CB7E),
    val wxCloudFill: Color = Color(0x1FC3D0CD),
    val wxRain: Color = Color(0xFF7FB2E5),

    // ---- errors (the only red) ----------------------------------------------
    val danger: Color = Color(0xFFE37B7B),
    val dangerSoft: Color = Color(0xFFE06767),
    val dangerBg: Color = Color(0xFF3A2E2E),
    val onDanger: Color = Color(0xFF241012),

    /**
     * Unavailable / unknown. A desaturated slate that is NOT "off"; always
     * pair it with the word (see [com.custom.astrion.ui.UnavailableLabel]).
     */
    val unavailable: Color = Color(0xFF7B8C96),
    val unavailableAlpha: Float = 0.4f,

    // ---- overlays -----------------------------------------------------------
    val hairline: Color = Color(0x1AFFFFFF),
    val hairlineStrong: Color = Color(0x33FFFFFF),
    /** 8% white glass fill. */
    val glass: Color = Color(0x14FFFFFF),
    /** A segmented control's track: a faint lift of whatever panel it sits on. */
    val segmentTrack: Color = Color(0x0BFFFFFF),
    val divider: Color = Color(0x333A4F57),
    val scrim: Color = Color(0xCC0B1015),
    /** Deep scrim behind the ringing alarm. */
    val scrimDark: Color = Color(0xF00A0F14),

    // ---- alarm screen: Yacht Race into night --------------------------------
    val alarmTop: Color = Color(0xFF2F3E4C),
    val alarmMid: Color = Color(0xFF1D2A38),
    val alarmBottom: Color = Color(0xFF141D27),

    // ---- screensaver ----------------------------------------------------------
    val dayClock: Color = Color(0xFFDDE7E3),
    /** Night face ground. */
    val nightFace: Color = Color(0xFF0C0E10),
    /** Clock and title on the night face, a dimmed Baby Doll (≈7.5:1). */
    val nightInk: Color = Color(0xFFC29690),
    /** Date, artist, meta, progress (≈4.8:1). */
    val nightInk2: Color = Color(0xFF9C7570),
    val nightTrack: Color = Color(0xFF1A1D21),
)
