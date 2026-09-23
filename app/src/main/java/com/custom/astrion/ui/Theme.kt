package com.custom.astrion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Astrion design tokens — the single source of colour, type, spacing, radius
 * and "elevation" for the whole app. Nothing outside `ui/` should spell a
 * `Color(0x…)` or a literal `fontSize`; if a screen needs a value that isn't
 * here, add a token rather than a literal.
 *
 * Design language ("Club night"):
 *  - A deep ink-teal page. Surfaces step UP in tone to show layering — there
 *    are no shadows, blurs or glows (the MT6580 pays for every one of those).
 *  - Exactly one warm signal: amber means ON (a light, an open blind, a
 *    playing thing's highlight). Blue means SELECTED / navigation / info.
 *    Green means good/healthy (locked, playing, level). Red means danger or
 *    failure. Lilac means UNAVAILABLE — a hue nothing else uses, always paired
 *    with an icon and the word, because on this palette "off" is already grey.
 *  - Contrast (WCAG, computed against `card`): textPrimary 10.5:1,
 *    textSecondary 6.4:1, accent 5.5:1, on 7.5:1, good 6.3:1, unavailable
 *    6.0:1, danger 4.4:1 (used ≥ 14sp bold or as an icon). `textMuted` is
 *    3.5:1 and is ONLY for decoration or text that is never tapped.
 *  - Card vs page is 1.35:1 (it was 1.14:1 — cards were separated by gaps
 *    alone and disappeared at low brightness).
 */
object AstrionTheme {

    // ---- surfaces, darkest → lightest ("elevation" by tone) --------------
    /** Level 0: the page. */
    val pageBg = Color(0xFF0F2328)
    /** Pinned header / footer bands and the sheet scrim's base. */
    val pinnedTopBg = Color(0xFF0B1D22)
    val pinnedBottomBg = Color(0xFF0B1D22)
    /** Recessed wells: segmented-control tracks, unselected chips. */
    val controlSunken = Color(0xFF17313A)
    /** Level 1: cards and sheets. */
    val cardBg = Color(0xFF1D3A44)
    /** Level 2: raised elements inside a card (tiles, icon wells). */
    val raised = Color(0xFF284955)
    /** Level 3: buttons and chips. */
    val controlBg = Color(0xFF2E4F5B)
    /** Pressed state for level-3 controls. */
    val controlPressed = Color(0xFF3B616E)
    /** Progress / level tracks. */
    val trackBg = Color(0xFF13292F)
    /** Full-screen scrim behind sheets and modal overlays. */
    val scrim = Color(0xE6060D10)
    /** Hairline dividers inside a card. */
    val divider = Color(0xFF284955)

    // ---- text ---------------------------------------------------------------
    val textPrimary = Color(0xFFE8F1F2)
    val textSecondary = Color(0xFFA9C1C7)
    /** Glyphs / labels on level-3 controls (7.7:1 on controlBg). */
    val textOnControl = Color(0xFFD3E2E6)
    /** Decoration and never-tapped captions only (3.5:1). */
    val textMuted = Color(0xFF728E99)

    // ---- signal colours -----------------------------------------------------
    /** Selection / navigation / information. */
    val accent = Color(0xFF7AB0FF)
    /** Filled selection (white text 5.7:1). */
    val accentStrong = Color(0xFF3B5BDB)
    val onAccent = Color(0xFFFFFFFF)
    /** "On" amber. */
    val on = Color(0xFFFFC24B)
    /** Ink on an amber fill (10.3:1). */
    val onBg = Color(0xFF2A1C00)
    /** Amber tint well behind an "on" icon (opaque, no alpha layer). */
    val onWell = Color(0xFF4A4127)
    val good = Color(0xFF5FCFAD)
    val goodWell = Color(0xFF1E4A45)
    val danger = Color(0xFFF07878)
    val dangerBg = Color(0xFF3A2528)
    /** Strong danger fill (hold-to-confirm buttons) with [dangerInk] 8.2:1. */
    val dangerStrong = Color(0xFF7A2430)
    val dangerStrongHi = Color(0xFFB8323F)
    val dangerInk = Color(0xFFFFE3E3)
    /** Unavailable / unknown — lilac, never used for anything else. */
    val unavailable = Color(0xFFC0AEE3)
    val unavailableWell = Color(0xFF2F2F48)
    /** Config-notice banner (developer diagnostic). */
    val noticeBg = Color(0xFF4A3B1E)
    val noticeInk = Color(0xFFF3D48E)

    // ---- legacy aliases (kept so the schema of this object never breaks) ----
    /** Merged into [cardBg]: the two were 1.06:1 apart — invisible. */
    val cardBgAlt = cardBg

    // ---- weather / range bars ----------------------------------------------
    val rangeLow = Color(0xFF3DD68C)
    val rangeHigh = Color(0xFF9BE7C4)

    // ---- floorplan overlays --------------------------------------------------
    /** Letterbox behind the plan (near-black so it reads as the plan's edge). */
    val planBg = Color(0xFF0A1418)
    /** Multiply tint that darkens the photoreal plan for a dark room. */
    val planDim = Color(0xFF9AA3A6)
    /** Icon well on the plan: off / on / unavailable (opaque = cheap). */
    val planOffWell = Color(0xFF1E2F35)
    val planOnWell = Color(0xFF6B5320)
    val planUnavailableWell = Color(0xFF3A3150)
    val planIconOff = Color(0xFFD9E3E6)
    val planIconOn = Color(0xFFFFD37A)
    /** Default radar dot colours when a block gives none. */
    val radarFill = Color(0xD9155E6E)
    val radarAccent = Color(0xFF33CBDA)
    val radarLabel = Color(0xFFDCF1F4)
    /** Robot vacuum glyph. */
    val vacBody = Color(0xFF3A4A52)
    val vacBump = Color(0xFF9FB0B8)
    val vacDock = Color(0xFF1E262C)

    // ---- media ------------------------------------------------------------------
    /** Multiply tint that darkens the blurred album-art backdrop (no alpha layer). */
    val artBackdropTint = Color(0xFF3C4B50)
    /** Placeholder well when there is no art. */
    val artPlaceholder = Color(0xFF284955)

    // ---- tiles ------------------------------------------------------------------
    /** Default `switch` card fill while on (its `on_color` overrides). */
    val switchOnDefault = Color(0xFF2E5A46)
    /** `fan` card fill while on. */
    val fanOnBg = Color(0xFF2B3A67)

    // ---- scenes ---------------------------------------------------------------
    val sceneDefault = Color(0xFF2A4954)
    val sceneInkDark = Color(0xFF141414)
    val sceneInkLight = Color(0xFFF0F2F6)

    // ---- light presets (data, not theme — but centralised here) ------------
    val presetPurple = Color(0xFF9B59B6)
    val presetBlue = Color(0xFF4A90D9)
    val presetPeach = Color(0xFFFFCBA4)
    /** Brightness fill when a light reports no colour. */
    val lightNeutralFill = Color(0xFFFFD9A0)

    // ---- alarm popup ("dawn") -------------------------------------------------
    object Alarm {
        val navyTop = Color(0xFF16345A)
        val navyMid = Color(0xFF10284A)
        val navyBottom = Color(0xFF0A1A31)
        val dawn = Color(0xFFFFB347)
        val dusk = Color(0xFF7AB0FF)
        val ink = Color(0xFFF3F6FA)
        val inkSoft = Color(0xFFAFC0D2)
        val inkFaint = Color(0xFF8FA3B9)
        val goldHi = Color(0xFFF6C75A)
        val goldLo = Color(0xFFDC9A22)
        val goldInk = Color(0xFF3A2605)
        val ringTrack = Color(0xFF2A4064)
        val card = Color(0xFF1D3B63)
        val cardEdge = Color(0xFF2E4E7A)
        val glyphWell = Color(0xFF2B4A77)
    }

    // ---- the remote's coloured buttons (key map legend) -------------------------
    val keyRed = Color(0xFFE05555)
    val keyGreen = Color(0xFF4CB860)
    val keyBlue = Color(0xFF4A8FE0)
    val keyYellow = Color(0xFFE8C440)

    // ---- IR mode ---------------------------------------------------------------
    val irBadge = Color(0xFFE0663A)
    val irBadgeInk = Color(0xFF20120C)

    // ---- legacy TextUnit sizes (prefer AstrionType styles) ------------------
    val display = 34.sp
    val title = 17.sp
    val body = 14.sp
    val label = 12.sp
}

/**
 * Type scale. Seven roles; 12sp is the floor (10–11sp text was ~14px tall at
 * 220dpi and unreadable across a dim room).
 */
object AstrionType {
    /** Hero numbers: the climate setpoint, the alarm clock uses its own. */
    val hero = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp)
    /** Big readouts: header clock-free weather temp, light %. */
    val display = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Light, lineHeight = 34.sp)
    /** Sheet titles, full-player track title. */
    val headline = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
    /** Card titles. */
    val title = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    /** Body copy and button labels. */
    val body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
    val bodyStrong = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
    /** Secondary lines, chips, captions. The floor. */
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
    /** Uppercase section headings. */
    val section = TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, lineHeight = 16.sp,
    )
    /** Alarm popup clock and its am/pm. */
    val alarmClock = TextStyle(fontSize = 88.sp, fontWeight = FontWeight.ExtraLight, lineHeight = 92.sp)
    val alarmAmPm = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Light, lineHeight = 32.sp)
    /** Snooze countdown inside its ring. */
    val countdown = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Light, lineHeight = 32.sp)
    /** Letter-spaced status words ("WAKE UP", "IR MODE"). */
    val shout = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, lineHeight = 18.sp)
    /** Big pill-button labels (alarm). */
    val button = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
    /** The page header's date / time. */
    val header = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, lineHeight = 18.sp,
    )
}

/** Spacing scale (dp). Everything is a multiple of 2, most of 4. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    /** Page side gutter and gap between cards. */
    val gutter = 10.dp
    /** Standard inner padding of a card. */
    val card = 14.dp
}

/** Corner radii. Four values, no others. */
object Radius {
    /** Cards and sheets' inner panels. */
    val card = 18.dp
    /** Buttons, tiles, chips, wells. */
    val control = 12.dp
    /** Small inner elements (segmented thumbs, posters). */
    val small = 8.dp
    /** Sheets. */
    val sheet = 22.dp
}

/** Touch-target sizes. */
object Touch {
    /** The minimum for anything a thumb hits without aiming. */
    val min = 48.dp
    /** Allowed only inside dense rows where 4dp gaps separate neighbours. */
    val compact = 44.dp
}

/**
 * Home Assistant state strings are snake_case machine values (`fan_only`,
 * `partlycloudy`). This is the one place that makes them human.
 */
fun String.humanise(): String =
    replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

/**
 * Home Assistant weather conditions are single-token slugs, so [humanise]
 * alone leaves them as-is. These are a closed set, so map them properly and
 * fall back to [humanise] for anything unrecognised.
 */
fun weatherLabel(condition: String): String = when (condition) {
    "clear-night" -> "Clear night"
    "partlycloudy" -> "Partly cloudy"
    "lightning-rainy" -> "Thunderstorms"
    "snowy-rainy" -> "Sleet"
    "windy-variant" -> "Windy"
    "pouring" -> "Heavy rain"
    "exceptional" -> "Severe"
    else -> condition.replace('-', ' ').humanise()
}

/** Parse "#RRGGBB" (opaque) or "#AARRGGBB" / an ARGB number into a Color. */
fun parseHexColor(v: Any?): Color? = when (v) {
    is Number -> Color(v.toLong())
    is String -> {
        val h = v.trim().removePrefix("#")
        val n = h.toLongOrNull(16)
        when {
            n == null -> null
            h.length <= 6 -> Color(0xFF000000L or n)
            else -> Color(n)
        }
    }
    else -> null
}
