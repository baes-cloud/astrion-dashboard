package com.custom.astrion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/**
 * The single source of colour and type for the dashboard.
 *
 * Before this existed the palette was ~70 raw `0xFF…` literals spread across 22
 * files, which measured out as roughly 12 colours with 70 spellings — e.g.
 * `0xFF2C4C58` (9 uses) and `0xFF2C4D59` (7 uses) differ by one step in green
 * and one in blue, a contrast ratio of 1.01:1, i.e. the same colour to any eye.
 * Two of those pairs have been merged here deliberately; everything else keeps
 * its exact previous value so nothing shifts visually.
 *
 * One value did change on purpose: `accentStrong` was `0xFF4C6EF5`, which put
 * white 13sp text at 4.32:1 — the only real contrast failure in the app, and on
 * the element that carries state (the selected climate mode chip). Darkened to
 * `0xFF3B5BDB` for 5.6:1.
 */
object AstrionTheme {

    // ---- surfaces -----------------------------------------------------------
    val pageBg = Color(0xFF122A32)
    val pinnedTopBg = Color(0xFF0E2229)
    val pinnedBottomBg = Color(0xFF13262D)

    /** Default card fill. */
    val cardBg = Color(0xFF1B343D)
    /** Secondary card fill — cover/switch tiles used this historically. */
    val cardBgAlt = Color(0xFF1E3841)
    /** Raised element inside a card (grid buttons, scene tile faces). */
    val raised = Color(0xFF2A4954)
    /** Control chrome: small round buttons, chips. Merges the old 2C4D59. */
    val controlBg = Color(0xFF2C4C58)
    /** Recessed control, e.g. the lip under a scene tile. */
    val controlSunken = Color(0xFF23414B)
    /** Slider / progress track. */
    val trackBg = Color(0xFF152B33)

    // NOTE: a 1dp hairline card edge was tried here (card-to-page contrast is
    // only 1.14–1.21:1, so edges are carried by the 10dp gaps alone) and
    // removed by preference — the outline read as noise on the light and
    // climate cards. Cards are separated by spacing, not strokes.

    // ---- text ---------------------------------------------------------------
    val textPrimary = Color(0xFFE6F0F1)   // 11.28:1 on cardBg
    val textSecondary = Color(0xFF93AFB6) // 5.64:1
    val textOnControl = Color(0xFFCBDCE0)
    val textMuted = Color(0xFF5A7783)

    // ---- state --------------------------------------------------------------
    val accent = Color(0xFF6EA8FE)
    /** Was 0xFF4C6EF5 (4.32:1 with white). Darkened for AA. */
    val accentStrong = Color(0xFF3B5BDB)
    /** "On" amber. */
    val on = Color(0xFFFFC24B)
    val onBg = Color(0xFF241A00)
    val danger = Color(0xFFE06767)
    val dangerBg = Color(0xFF3A2E2E)
    val good = Color(0xFF57C4A3)

    /**
     * Unavailable / unknown. Deliberately a desaturated slate that is NOT the
     * same as "off" — on this palette "off" already reads as grey, so the
     * colour alone can never carry the distinction. Always pair it with the
     * literal word (see [com.custom.astrion.ui.UnavailableLabel]).
     */
    val unavailable = Color(0xFF7B8C96)

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
