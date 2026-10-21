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
    /** Antonio: tall, narrow numerals for readouts (temperatures, volume, the clock). */
    val readoutFont: FontFamily,

    // ---- E2E Contract Tokens (DesignSystemModel.TYPOGRAPHY) -----------------
    val display: TextStyle,                            // 34sp / 40sp line height
    val title: TextStyle,                              // 17sp / 22sp line height
    val body: TextStyle,                               // 14sp / 18sp line height
    val label: TextStyle,                              // 12sp / 16sp line height

    // ---- Material 3 & Extended Scale ----------------------------------------
    val displayLarge: TextStyle,                       // 81sp (Screensaver giant clock)
    val displayMedium: TextStyle,                      // 40sp (Climate giant temperature readout)
    val displaySmall: TextStyle,                       // 32sp (Weather card clock)
    val titleLarge: TextStyle,                         // 20sp (Dialog titles, media header)
    val titleMedium: TextStyle,                        // 17sp (Card names, climate header)
    val titleSmall: TextStyle,                         // 15sp (Tile names, alert titles)
    val bodyLarge: TextStyle,                          // 15sp (Weather conditions, date line)
    val bodyMedium: TextStyle,                         // 14sp (Default body text, toasts)
    val bodySmall: TextStyle,                          // 13sp (Secondary captions, pills)
    val labelLarge: TextStyle,                         // 13sp Bold, 1sp tracking (Status header)
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
        Font(R.font.manrope_800, FontWeight.ExtraBold),
    )
    val readoutFont = FontFamily(
        Font(R.font.antonio_200, FontWeight.ExtraLight),
        Font(R.font.antonio_300, FontWeight.Light),
        Font(R.font.antonio_400, FontWeight.Normal),
    )
    // The redesign sets headings in Manrope too; Antonio carries the numerals.
    val headingFont = bodyFont
    return AstrionTypography(
        bodyFont = bodyFont,
        headingFont = headingFont,
        readoutFont = readoutFont,
        display = TextStyle(fontFamily = readoutFont, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Light),
        title = TextStyle(fontFamily = bodyFont, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        body = TextStyle(fontFamily = bodyFont, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        label = TextStyle(fontFamily = bodyFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),

        displayLarge = TextStyle(fontFamily = bodyFont, fontSize = 81.sp, lineHeight = 81.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.5).sp),
        displayMedium = TextStyle(fontFamily = readoutFont, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Light),
        displaySmall = TextStyle(fontFamily = readoutFont, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Light),
        titleLarge = TextStyle(fontFamily = headingFont, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontFamily = bodyFont, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontFamily = bodyFont, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontFamily = bodyFont, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyMedium = TextStyle(fontFamily = bodyFont, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        bodySmall = TextStyle(fontFamily = bodyFont, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(fontFamily = bodyFont, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
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
 */
@Immutable
data class AstrionTypeSizes(
    /** The "ON" badge on a scene tile: two capitals, not something to read. */
    val micro: TextUnit = 8.sp,
    /** The smallest text: time stamps, captions, small labels. */
    val label: TextUnit = MIN_TEXT,
    val small: TextUnit = 13.sp,
    val body: TextUnit = 14.sp,
    val bodyLarge: TextUnit = 15.sp,
    val emphasis: TextUnit = 16.sp,
    val title: TextUnit = 17.sp,
    val titleLarge: TextUnit = 18.sp,
    val heading: TextUnit = 19.sp,
    val headingLarge: TextUnit = 20.sp,
    val displayXs: TextUnit = 22.sp,
    val displaySm: TextUnit = 26.sp,
    val displayMd: TextUnit = 28.sp,
    val displayLg: TextUnit = 32.sp,
    val displayXl: TextUnit = 34.sp,
    /** Climate's set temperature. */
    val readout: TextUnit = 40.sp,
    /** A popup's big number: a light's brightness, a set temperature. */
    val readoutLarge: TextUnit = 64.sp,
    /** The screensaver clock (Antonio, tall and narrow). */
    val clock: TextUnit = 120.sp,
    /** The ringing alarm's clock. */
    val alarmClock: TextUnit = 88.sp,
    /** The ambient rest clock. */
    val restClock: TextUnit = 92.sp,
)

/** The smallest text size anything is set in (see [AstrionTypeSizes]). */
val MIN_TEXT: TextUnit = 12.sp
