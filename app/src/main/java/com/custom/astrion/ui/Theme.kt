package com.custom.astrion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.custom.astrion.R
import androidx.compose.ui.unit.sp

/**
 * The single source of colour and type for the dashboard.
 *
 * Cards still carry some one-off literals of their own; those were remapped
 * onto this palette when it changed (2026-09), so they stay in the same family.
 */
object AstrionTheme {

    // Palette: Porter's Paints card, 2026-09 — Explorer Blue #A4B7AF,
    // Dark Newport Blue #86A194, Gunmetal Grey #56736F, Yacht Race #2F3E4C.
    // Yacht Race is the base the surfaces are built from (darkened for the
    // page, stepped up for cards and controls); the three greens carry text,
    // accents and selected state.

    // ---- surfaces -----------------------------------------------------------
    val pageBg = Color(0xFF1A232C)
    val pinnedTopBg = Color(0xFF151D25)
    val pinnedBottomBg = Color(0xFF182129)

    /** Default card fill. */
    val cardBg = Color(0xFF243140)
    /** Secondary card fill — cover/switch tiles used this historically. */
    val cardBgAlt = Color(0xFF283646)
    /** Raised element inside a card (grid buttons, scene tile faces). */
    val raised = Color(0xFF34454F)
    /** Control chrome: small round buttons, chips. */
    val controlBg = Color(0xFF3A4F57)
    /** Recessed control, e.g. the lip under a scene tile. */
    val controlSunken = Color(0xFF2C3B45)
    /** Slider / progress track. */
    val trackBg = Color(0xFF1C2630)

    // NOTE: a 1dp hairline card edge was tried here and removed by preference —
    // cards are separated by spacing, not strokes.

    // ---- text ---------------------------------------------------------------
    val textPrimary = Color(0xFFEEF2EF)
    /** Explorer Blue. */
    val textSecondary = Color(0xFFA4B7AF)
    val textOnControl = Color(0xFFD3DED9)
    val textMuted = Color(0xFF6F8583)

    // ---- state --------------------------------------------------------------
    /** A lifted Dark Newport Blue, readable as text/icons on the dark cards. */
    val accent = Color(0xFF8FC0AA)
    /** Gunmetal Grey: selected chips and filled buttons under white text. */
    val accentStrong = Color(0xFF4E6E69)
    /** "On" amber — lights stay warm; the palette has no warm colour. */
    val on = Color(0xFFFFC24B)
    val onBg = Color(0xFF241A00)
    val danger = Color(0xFFE06767)
    val dangerBg = Color(0xFF3A2E2E)
    val good = Color(0xFF86C0A6)

    /**
     * Unavailable / unknown. Deliberately a desaturated slate that is NOT the
     * same as "off" — always pair it with the literal word (see
     * [com.custom.astrion.ui.UnavailableLabel]).
     */
    val unavailable = Color(0xFF7B8C96)

    // ---- fonts --------------------------------------------------------------
    /** Manrope: everything by default (provided as the root text style). */
    val bodyFont = FontFamily(
        Font(R.font.manrope_400, FontWeight.Normal),
        Font(R.font.manrope_500, FontWeight.Medium),
        Font(R.font.manrope_600, FontWeight.SemiBold),
        Font(R.font.manrope_700, FontWeight.Bold),
    )
    /** Syne: big numerals and headings (clock, temperatures, titles). */
    val headingFont = FontFamily(
        Font(R.font.syne_500, FontWeight.Light),
        Font(R.font.syne_500, FontWeight.Normal),
        Font(R.font.syne_500, FontWeight.Medium),
        Font(R.font.syne_600, FontWeight.SemiBold),
        Font(R.font.syne_700, FontWeight.Bold),
    )

    // ---- type ---------------------------------------------------------------
    // Sixteen distinct sizes were in use, eight of them clustered in 11–18sp
    // doing broadly two jobs. These four roles cover essentially everything.
    val display = 34.sp
    val title = 17.sp
    val body = 14.sp
    val label = 12.sp
}

/**
 * Home Assistant state strings are snake_case machine values (`fan_only`,
 * `partlycloudy`) and were reaching the screen with only their first letter
 * capitalised — the Climate page read "Fan_only" and the Main page
 * "Partlycloudy". This is the one place that fixes both.
 */
fun String.humanise(): String =
    replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

/**
 * Home Assistant weather conditions are single-token slugs, so [humanise] alone
 * leaves them as-is — the Main page was reading "Partlycloudy". These are a
 * closed set, so map them properly and fall back to [humanise] for anything
 * unrecognised.
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
