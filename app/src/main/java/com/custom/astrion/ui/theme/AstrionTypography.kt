package com.custom.astrion.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.custom.astrion.R

@Immutable
data class AstrionTypography(
    val bodyFont: FontFamily,
    val headingFont: FontFamily,
    val readoutFont: FontFamily,

    // ---- E2E Contract Tokens (DesignSystemModel.TYPOGRAPHY) -----------------
    val display: TextStyle,                            // 34sp / 40sp line height
    val title: TextStyle,                              // 16sp / 20sp line height
    val body: TextStyle,                               // 14sp / 18sp line height
    val label: TextStyle,                              // 12sp / 16sp line height

    // ---- Material 3 & Extended Scale ----------------------------------------
    val displayLarge: TextStyle,                       // 81sp (Screensaver giant clock)
    val displayMedium: TextStyle,                      // 40sp (Climate giant temperature readout)
    val displaySmall: TextStyle,                       // 34sp (Weather card clock)
    val titleLarge: TextStyle,                         // 22sp (Dialog titles, media header)
    val titleMedium: TextStyle,                        // 16sp (Card names, climate header)
    val titleSmall: TextStyle,                         // 14sp (Tile names, alert titles)
    val bodyLarge: TextStyle,                          // 14sp (Weather conditions, date line)
    val bodyMedium: TextStyle,                         // 14sp (Default body text, toasts)
    val bodySmall: TextStyle,                          // 12sp (Secondary captions, pills)
    val labelLarge: TextStyle,                         // 12sp Bold, 1sp tracking (Status header)
    val labelMedium: TextStyle,                        // 12sp SemiBold, 1sp tracking (Section headers)
    val labelSmall: TextStyle,                         // 12sp Medium, 0.5sp tracking (Connection pill)
    val labelMicro: TextStyle,                         // 8sp Bold, 0.16em tracking ("ON" badge)
    val wordmark: TextStyle,                           // 16sp Bold, 0.318em tracking (BÆOREMOTE footer)

    /** The font sizes in use, for text that sets its own weight and family. */
    val sizes: AstrionTypeSizes = AstrionTypeSizes(),
) {
    // Backwards-compatible TextUnit accessors
    val displaySize: TextUnit get() = display.fontSize
    val titleSize: TextUnit get() = title.fontSize
    val bodySize: TextUnit get() = body.fontSize
    val labelSize: TextUnit get() = label.fontSize
}

fun defaultAstrionTypography(): AstrionTypography {
    val bodyFont = FontFamily(
        Font(R.font.manrope_400, FontWeight.Normal),
        Font(R.font.manrope_500, FontWeight.Medium),
        Font(R.font.manrope_600, FontWeight.SemiBold),
        Font(R.font.manrope_700, FontWeight.Bold),
    )
    val headingFont = FontFamily(
        Font(R.font.syne_400, FontWeight.Thin),
        Font(R.font.syne_500, FontWeight.Light),
        Font(R.font.syne_500, FontWeight.Normal),
        Font(R.font.syne_500, FontWeight.Medium),
        Font(R.font.syne_600, FontWeight.SemiBold),
        Font(R.font.syne_700, FontWeight.Bold),
    )
    val readoutFont = FontFamily(
        Font(R.font.antonio_200, FontWeight.ExtraLight),
        Font(R.font.antonio_300, FontWeight.Light),
        Font(R.font.antonio_400, FontWeight.Normal),
    )
    return AstrionTypography(
        bodyFont = bodyFont,
        headingFont = headingFont,
        readoutFont = readoutFont,
        display = TextStyle(fontFamily = headingFont, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal),
        title = TextStyle(fontFamily = bodyFont, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        body = TextStyle(fontFamily = bodyFont, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        label = TextStyle(fontFamily = bodyFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),

        displayLarge = TextStyle(fontFamily = bodyFont, fontSize = 81.sp, lineHeight = 81.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.5).sp),
        displayMedium = TextStyle(fontFamily = headingFont, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Light),
        displaySmall = TextStyle(fontFamily = headingFont, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Light),
        titleLarge = TextStyle(fontFamily = headingFont, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontFamily = bodyFont, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontFamily = bodyFont, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontFamily = bodyFont, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        bodyMedium = TextStyle(fontFamily = bodyFont, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        bodySmall = TextStyle(fontFamily = bodyFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(fontFamily = bodyFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        labelMedium = TextStyle(fontFamily = bodyFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
        labelSmall = TextStyle(fontFamily = bodyFont, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
        labelMicro = TextStyle(fontFamily = bodyFont, fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.16.em),
        wordmark = TextStyle(fontFamily = headingFont, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.318.em),
    )
}

/**
 * Every font size the app uses, smallest to largest. A `fontSize` comes from
 * here, never a literal, so the scale can be read (and changed) in one place.
 * Nothing is read below [MIN_TEXT] (12 sp, at arm's length on a 349 dp
 * panel); [micro] is the one exception, the "ON" badge's capitals.
 *
 * Seven sizes for the interface, then the big numerals. It used to be
 * fourteen, with 12-13-14-15-16-17-18-19-20 all in use: steps of one sp that
 * nobody can tell apart but that never quite lined up either. Each of those
 * went to its neighbour (13 to 12, 15 to 14, 17 to 16, 19 to 18, 20 to 22,
 * 26 to 28, 32 to 34).
 */
@Immutable
data class AstrionTypeSizes(
    /** The "ON" badge on a scene tile: two capitals, not something to read. */
    val micro: TextUnit = 8.sp,
    /** The smallest text: time stamps, captions, small labels, secondary lines. */
    val label: TextUnit = MIN_TEXT,
    val body: TextUnit = 14.sp,
    /** Card names and anything that leads a row. */
    val emphasis: TextUnit = 16.sp,
    val titleLarge: TextUnit = 18.sp,
    val displayXs: TextUnit = 22.sp,
    val displayMd: TextUnit = 28.sp,
    val displayXl: TextUnit = 34.sp,
    /** Climate's set temperature. */
    val readout: TextUnit = 40.sp,
    /** Light brightness percentage readout in LightDetailDialog. */
    val readoutLarge: TextUnit = 64.sp,
    // The three clocks keep their own sizes: each is fitted to the width of
    // its own screen, and one size for all of them would clip one or shrink
    // another.
    /** The screensaver clock. */
    val clock: TextUnit = 81.sp,
    /** The ringing alarm's clock. */
    val alarmClock: TextUnit = 88.sp,
    /** The ambient rest clock. */
    val restClock: TextUnit = 92.sp,
)

/** The smallest text size anything is set in (see [AstrionTypeSizes]). */
val MIN_TEXT: TextUnit = 12.sp
