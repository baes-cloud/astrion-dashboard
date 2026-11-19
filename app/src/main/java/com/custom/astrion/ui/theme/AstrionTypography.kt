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
    val displayMedium: TextStyle,                      // 44sp (Climate temperature readout, Manrope 400 tnum)
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
    val labelMicro: TextStyle,                         // 10sp Bold, 0.14em tracking ("ON" / "NOW" badge)
    val wordmark: TextStyle,                           // 16sp Bold, 0.318em tracking (BÆOREMOTE footer)

    /** The font sizes in use, for text that sets its own weight and family. */
    val sizes: AstrionTypeSizes = AstrionTypeSizes(),
) {
    val displaySize: TextUnit get() = display.fontSize
    val titleSize: TextUnit get() = title.fontSize
    val bodySize: TextUnit get() = body.fontSize
    val labelSize: TextUnit get() = label.fontSize
}

/**
 * Rule of Two Typography:
 *  - [bodyFont] (Manrope 400–700 + `tnum` on numeric readouts): all UI copy,
 *    card/track titles, Climate & Light Sheet numerals (using Regular 400).
 *  - [headingFont] (Syne 400–700, properly mapped): wordmarks & cinema/alert
 *    display headlines only.
 *  - Antonio retired cleanly.
 */
fun defaultAstrionTypography(): AstrionTypography {
    val bodyFont = FontFamily(
        Font(R.font.manrope_400, FontWeight.Light),
        Font(R.font.manrope_400, FontWeight.Normal),
        Font(R.font.manrope_500, FontWeight.Medium),
        Font(R.font.manrope_600, FontWeight.SemiBold),
        Font(R.font.manrope_700, FontWeight.Bold),
    )
    val headingFont = FontFamily(
        Font(R.font.syne_400, FontWeight.Thin),
        Font(R.font.syne_400, FontWeight.Light),
        Font(R.font.syne_400, FontWeight.Normal),
        Font(R.font.syne_500, FontWeight.Medium),
        Font(R.font.syne_600, FontWeight.SemiBold),
        Font(R.font.syne_700, FontWeight.Bold),
    )
    return AstrionTypography(
        bodyFont = bodyFont,
        headingFont = headingFont,
        readoutFont = bodyFont,
        display = TextStyle(fontFamily = headingFont, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal),
        title = TextStyle(fontFamily = bodyFont, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        body = TextStyle(fontFamily = bodyFont, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        label = TextStyle(fontFamily = bodyFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),

        displayLarge = TextStyle(fontFamily = bodyFont, fontSize = 81.sp, lineHeight = 81.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.5).sp, fontFeatureSettings = "tnum"),
        displayMedium = TextStyle(fontFamily = bodyFont, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
        displaySmall = TextStyle(fontFamily = bodyFont, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
        titleLarge = TextStyle(fontFamily = headingFont, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontFamily = bodyFont, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontFamily = bodyFont, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontFamily = bodyFont, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        bodyMedium = TextStyle(fontFamily = bodyFont, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        bodySmall = TextStyle(fontFamily = bodyFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(fontFamily = bodyFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        labelMedium = TextStyle(fontFamily = bodyFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
        labelSmall = TextStyle(fontFamily = bodyFont, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
        labelMicro = TextStyle(fontFamily = bodyFont, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.14.em),
        wordmark = TextStyle(fontFamily = headingFont, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.318.em),
    )
}

@Immutable
data class AstrionTypeSizes(
    /** Short status pills ("ON", "NOW", "VOL KEYS"). */
    val micro: TextUnit = 10.sp,
    /** Smallest readable text: timestamps, captions, labels, secondary lines. */
    val label: TextUnit = MIN_TEXT,
    val body: TextUnit = 14.sp,
    /** Card names and primary row leads. */
    val emphasis: TextUnit = 16.sp,
    val titleLarge: TextUnit = 18.sp,
    val displayXs: TextUnit = 22.sp,
    val displayMd: TextUnit = 28.sp,
    val displayXl: TextUnit = 34.sp,
    /** Climate setpoint temperature (Manrope 400 tnum). */
    val readout: TextUnit = 44.sp,
    /** Light brightness percentage readout in LightDetailDialog (Manrope 400 tnum). */
    val readoutLarge: TextUnit = 56.sp,
    val clock: TextUnit = 81.sp,
    val alarmClock: TextUnit = 88.sp,
    val restClock: TextUnit = 92.sp,
)

val MIN_TEXT: TextUnit = 12.sp
