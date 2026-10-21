package com.custom.astrion.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import com.custom.astrion.ui.theme.AstrionColors
import com.custom.astrion.ui.theme.AstrionDesignSystemTheme
import com.custom.astrion.ui.theme.AstrionSurfaces
import com.custom.astrion.ui.theme.AstrionTypeSizes
import com.custom.astrion.ui.theme.AstrionTypography
import com.custom.astrion.ui.theme.LocalAstrionColors
import com.custom.astrion.ui.theme.LocalAstrionSurfaces
import com.custom.astrion.ui.theme.LocalAstrionTypography

/**
 * The one theme API. Every colour, font and size reads the design system's
 * CompositionLocals ([AstrionDesignSystemTheme] provides them at the root),
 * so a different palette (a night theme, say) only needs providing, and
 * nothing reads a static copy. Hence the getters are composable: read them
 * in composition, or capture them there for a draw lambda.
 *
 * The values live in [AstrionColors] and [AstrionTypography]; the names here
 * are the ones the cards use.
 */
object AstrionTheme {

    val colors: AstrionColors
        @Composable @ReadOnlyComposable get() = LocalAstrionColors.current
    val typography: AstrionTypography
        @Composable @ReadOnlyComposable get() = LocalAstrionTypography.current
    /** Font sizes: `fontSize = AstrionTheme.size.small`. */
    val size: AstrionTypeSizes
        @Composable @ReadOnlyComposable get() = LocalAstrionTypography.current.sizes
    val surfaces: AstrionSurfaces
        @Composable @ReadOnlyComposable get() = LocalAstrionSurfaces.current

    // ---- surfaces -----------------------------------------------------------
    val pageBg: Color @Composable @ReadOnlyComposable get() = colors.background
    val pinnedTopBg: Color @Composable @ReadOnlyComposable get() = colors.pinnedTopBg
    val pinnedBottomBg: Color @Composable @ReadOnlyComposable get() = colors.pinnedBottomBg
    /** Default card fill. */
    val cardBg: Color @Composable @ReadOnlyComposable get() = colors.surface
    /** Secondary card fill: cover/switch tiles, the floorplan canvas. */
    val cardBgAlt: Color @Composable @ReadOnlyComposable get() = colors.surfaceVariant
    /** Raised element inside a card (grid buttons, scene tile faces). */
    val raised: Color @Composable @ReadOnlyComposable get() = colors.raised
    /** Control chrome: small round buttons, chips. */
    val controlBg: Color @Composable @ReadOnlyComposable get() = colors.controlBg
    /** Recessed control, e.g. the lip under a scene tile. */
    val controlSunken: Color @Composable @ReadOnlyComposable get() = colors.controlSunken
    /** Slider / progress track. */
    val trackBg: Color @Composable @ReadOnlyComposable get() = colors.surfaceCard

    // ---- text ---------------------------------------------------------------
    val textPrimary: Color @Composable @ReadOnlyComposable get() = colors.onSurface
    val textSecondary: Color @Composable @ReadOnlyComposable get() = colors.onSurfaceMuted
    val textOnControl: Color @Composable @ReadOnlyComposable get() = colors.textOnControl
    val textMuted: Color @Composable @ReadOnlyComposable get() = colors.textMuted

    // ---- state --------------------------------------------------------------
    /** Hailstorm, lifted to read as text/icons on the dark cards. */
    val accent: Color @Composable @ReadOnlyComposable get() = colors.accent
    /** Gunmetal Grey: selected chips and filled buttons under white text. */
    val accentStrong: Color @Composable @ReadOnlyComposable get() = colors.accentStrong
    /** Warm amber, for lights that are on and nothing else. */
    val on: Color @Composable @ReadOnlyComposable get() = colors.lightOn
    val onBg: Color @Composable @ReadOnlyComposable get() = colors.onLightOn
    /** Baby Doll: "needs attention" (unlocked, open, warnings, mute) and the
     *  next alarm. Text/icons on it use [onBlush]. */
    val blush: Color @Composable @ReadOnlyComposable get() = colors.blush
    val onBlush: Color @Composable @ReadOnlyComposable get() = colors.onBlush
    val danger: Color @Composable @ReadOnlyComposable get() = colors.danger
    val dangerBg: Color @Composable @ReadOnlyComposable get() = colors.dangerBg
    val good: Color @Composable @ReadOnlyComposable get() = colors.accent

    // ---- night (docked screensaver) -----------------------------------------
    /** Clock and title on the night face (≈7.5:1 on [nightFace]). */
    val nightInk: Color @Composable @ReadOnlyComposable get() = colors.nightInk
    /** Date, artist, meta, progress and the BÆOREMOTE name (≈4.8:1). */
    val nightInk2: Color @Composable @ReadOnlyComposable get() = colors.nightInk2
    val nightFace: Color @Composable @ReadOnlyComposable get() = colors.nightFace

    /**
     * Unavailable / unknown. Deliberately a desaturated slate that is NOT the
     * same as "off": always pair it with the literal word (see
     * [com.custom.astrion.ui.UnavailableLabel]).
     */
    val unavailable: Color @Composable @ReadOnlyComposable get() = colors.unavailable

    // ---- fonts --------------------------------------------------------------
    /** Manrope: everything by default (provided as the root text style). */
    val bodyFont: FontFamily @Composable @ReadOnlyComposable get() = typography.bodyFont
    /** Syne: big numerals and headings (clock, temperatures, titles). */
    val headingFont: FontFamily @Composable @ReadOnlyComposable get() = typography.headingFont
    /** Antonio: readout numerals (temperatures, volume, brightness, clocks). */
    val readoutFont: FontFamily @Composable @ReadOnlyComposable get() = typography.readoutFont

    // ---- type roles ---------------------------------------------------------
    val display: TextUnit @Composable @ReadOnlyComposable get() = typography.displaySize
    val title: TextUnit @Composable @ReadOnlyComposable get() = typography.titleSize
    val body: TextUnit @Composable @ReadOnlyComposable get() = typography.bodySize
    val label: TextUnit @Composable @ReadOnlyComposable get() = typography.labelSize
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

/**
 * Material3 dressed in the Astrion palette, at the root of the window.
 *
 * Without it every `clickable` fell back to Compose's debug indication — a 30%
 * black overlay, barely visible on these dark surfaces — and Material3 pieces
 * (dropdown menus, the vacuum's mode list) rendered in the light default
 * theme, in Roboto, with 4dp corners. Here the ripple is light
 * ([LocalContentColor]), menus match the 12dp control radius, and the body
 * font is Manrope throughout.
 *
 * Also swaps in [StrongHaptics]: the platform's LongPress feedback on the
 * HA100 is two 1ms pulses, too faint to feel through the case.
 */
@Composable
fun AstrionMaterialTheme(content: @Composable () -> Unit) {
    AstrionDesignSystemTheme(content = content)
}

/**
 * A short real vibration for every haptic the app asks for. The platform's
 * LongPress pattern on this device is `[0, 1, 20, 21]` — two 1ms pulses — which
 * the small motor barely turns over for.
 */
class StrongHaptics(context: Context) : HapticFeedback {
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    fun pulse() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun tick() = pulse()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        pulse()
    }

    private companion object {
        const val PULSE_MS = 18L
    }
}
