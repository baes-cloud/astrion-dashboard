package com.custom.astrion.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.custom.astrion.R
import androidx.compose.ui.unit.sp

import com.custom.astrion.ui.theme.AstrionDesignSystem
import com.custom.astrion.ui.theme.AstrionDesignSystemTheme
import com.custom.astrion.ui.theme.AstrionColors
import com.custom.astrion.ui.theme.AstrionTypography
import com.custom.astrion.ui.theme.AstrionElevation
import com.custom.astrion.ui.theme.AstrionSurfaces
import com.custom.astrion.ui.theme.AstrionStates

/**
 * The single source of colour and type for the dashboard.
 *
 * Cards still carry some one-off literals of their own; those were remapped
 * onto this palette when it changed (2026-09), so they stay in the same family.
 * Delegated to AstrionDesignSystem for centralized token management.
 */
object AstrionTheme {

    // Palette: Porter's Paints, 2026-09 — Baby Doll #E6BCB6, Hailstorm #6E9E97,
    // Gunmetal Grey #4F726D, Yacht Race #2F3E4C. Yacht Race is the base the
    // surfaces are built from (darkened for the page, stepped up for cards and
    // controls); Hailstorm and Gunmetal carry accents and selected state, Baby
    // Doll is the warm accent (attention states, the next alarm, mute badge).

    // ---- surfaces -----------------------------------------------------------
    val pageBg get() = AstrionDesignSystem.defaultColors.background
    val pinnedTopBg get() = AstrionDesignSystem.defaultColors.pinnedTopBg
    val pinnedBottomBg get() = AstrionDesignSystem.defaultColors.pinnedBottomBg

    /** Default card fill. */
    val cardBg get() = AstrionDesignSystem.defaultColors.surfaceElevated
    /** Secondary card fill — cover/switch tiles used this historically. */
    val cardBgAlt get() = AstrionDesignSystem.defaultColors.surfaceVariant
    /** Raised element inside a card (grid buttons, scene tile faces). */
    val raised get() = AstrionDesignSystem.defaultColors.raised
    /** Control chrome: small round buttons, chips. */
    val controlBg get() = AstrionDesignSystem.defaultColors.controlBg
    /** Recessed control, e.g. the lip under a scene tile. */
    val controlSunken get() = AstrionDesignSystem.defaultColors.controlSunken
    /** Slider / progress track. */
    val trackBg get() = AstrionDesignSystem.defaultColors.trackBg

    // ---- text ---------------------------------------------------------------
    val textPrimary get() = AstrionDesignSystem.defaultColors.onSurface
    val textSecondary get() = AstrionDesignSystem.defaultColors.onSurfaceMuted
    val textOnControl get() = AstrionDesignSystem.defaultColors.textOnControl
    val textMuted get() = AstrionDesignSystem.defaultColors.textMuted

    // ---- state --------------------------------------------------------------
    /** Hailstorm, lifted to read as text/icons on the dark cards. */
    val accent get() = AstrionDesignSystem.defaultColors.accentSage
    /** Gunmetal Grey: selected chips and filled buttons under white text. */
    val accentStrong get() = AstrionDesignSystem.defaultColors.accentStrong
    /** "On" amber — lights stay warm; the palette has no warm colour. */
    val on get() = AstrionDesignSystem.defaultColors.accent
    val onBg get() = AstrionDesignSystem.defaultColors.onWarm
    /** Baby Doll: the warm accent — "needs attention" (unlocked, open) and
     *  the next alarm. Text/icons on it use [onBlush]. */
    val blush get() = AstrionDesignSystem.defaultColors.blush
    val onBlush get() = AstrionDesignSystem.defaultColors.onBlush
    val danger get() = AstrionDesignSystem.defaultColors.danger
    val dangerBg get() = AstrionDesignSystem.defaultColors.dangerBg
    val good get() = AstrionDesignSystem.defaultColors.successSoft

    // ---- night (docked screensaver) -----------------------------------------
    // BæoRemote rebrand, 2026-10: the night face moved from amber to a dimmed
    // Baby Doll rose.
    /** Clock and title on the night face (≈7.5:1 on [nightFace]). */
    val nightInk get() = AstrionDesignSystem.defaultColors.nightInk
    /** Date, artist, meta, progress and the BÆOREMOTE name (≈4.8:1). */
    val nightInk2 get() = AstrionDesignSystem.defaultColors.nightInk2
    /** Night face ground. */
    val nightFace get() = AstrionDesignSystem.defaultColors.nightFace

    /**
     * Unavailable / unknown. Deliberately a desaturated slate that is NOT the
     * same as "off" — always pair it with the literal word (see
     * [com.custom.astrion.ui.UnavailableLabel]).
     */
    val unavailable get() = AstrionDesignSystem.defaultColors.unavailable

    // ---- fonts --------------------------------------------------------------
    /** Manrope: everything by default (provided as the root text style). */
    val bodyFont get() = AstrionDesignSystem.defaultTypography.bodyFont
    /** Syne: big numerals and headings (clock, temperatures, titles). */
    val headingFont get() = AstrionDesignSystem.defaultTypography.headingFont

    // ---- type ---------------------------------------------------------------
    // Sixteen distinct sizes were in use, eight of them clustered in 11–18sp
    // doing broadly two jobs. These four roles cover essentially everything.
    val display get() = AstrionDesignSystem.defaultTypography.displaySize
    val title get() = AstrionDesignSystem.defaultTypography.titleSize
    val body get() = AstrionDesignSystem.defaultTypography.bodySize
    val label get() = AstrionDesignSystem.defaultTypography.labelSize

    @Composable
    operator fun invoke(
        colors: AstrionColors = AstrionDesignSystem.defaultColors,
        typography: AstrionTypography = AstrionDesignSystem.defaultTypography,
        elevation: AstrionElevation = AstrionDesignSystem.defaultElevation,
        surfaces: AstrionSurfaces = AstrionDesignSystem.defaultSurfaces,
        states: AstrionStates = AstrionDesignSystem.defaultStates,
        content: @Composable () -> Unit,
    ) {
        AstrionDesignSystemTheme(
            colors = colors,
            typography = typography,
            elevation = elevation,
            surfaces = surfaces,
            states = states,
            content = content,
        )
    }
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
