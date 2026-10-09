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

/**
 * The single source of colour and type for the dashboard.
 *
 * Cards still carry some one-off literals of their own; those were remapped
 * onto this palette when it changed (2026-09), so they stay in the same family.
 */
object AstrionTheme {

    // Palette: Porter's Paints, 2026-09 — Baby Doll #E6BCB6, Hailstorm #6E9E97,
    // Gunmetal Grey #4F726D, Yacht Race #2F3E4C. Yacht Race is the base the
    // surfaces are built from (darkened for the page, stepped up for cards and
    // controls); Hailstorm and Gunmetal carry accents and selected state, Baby
    // Doll is the warm accent (attention states, the next alarm, mute badge).

    // ---- surfaces -----------------------------------------------------------
    val pageBg = Color(0xFF1B242D)
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
    val textPrimary = Color(0xFFF1ECEA)
    val textSecondary = Color(0xFFA9BAB6)
    val textOnControl = Color(0xFFD9E3E0)
    val textMuted = Color(0xFF718583)

    // ---- state --------------------------------------------------------------
    /** Hailstorm, lifted to read as text/icons on the dark cards. */
    val accent = Color(0xFF8CBDB5)
    /** Gunmetal Grey: selected chips and filled buttons under white text. */
    val accentStrong = Color(0xFF4F726D)
    /** "On" amber — lights stay warm; the palette has no warm colour. */
    val on = Color(0xFFFFC24B)
    val onBg = Color(0xFF241A00)
    /** Baby Doll: the warm accent — "needs attention" (unlocked, open) and
     *  the next alarm. Text/icons on it use [onBlush]. */
    val blush = Color(0xFFE6BCB6)
    val onBlush = Color(0xFF4A2A26)
    val danger = Color(0xFFE37B7B)
    val dangerBg = Color(0xFF3A2E2E)
    val good = Color(0xFF8CBDB5)

    // ---- night (docked screensaver) -----------------------------------------
    // BæoRemote rebrand, 2026-10: the night face moved from amber to a dimmed
    // Baby Doll rose.
    /** Clock and title on the night face (≈7.5:1 on [nightFace]). */
    val nightInk = Color(0xFFC29690)
    /** Date, artist, meta, progress and the BÆOREMOTE name (≈4.8:1). */
    val nightInk2 = Color(0xFF9C7570)
    /** Night face ground. */
    val nightFace = Color(0xFF0C0E10)

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
    val f = AstrionTheme.bodyFont
    val base = remember { Typography() }
    val context = LocalContext.current
    val haptics = remember(context) { StrongHaptics(context) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AstrionTheme.accentStrong,
            onPrimary = Color.White,
            secondary = AstrionTheme.accent,
            background = AstrionTheme.pageBg,
            onBackground = AstrionTheme.textPrimary,
            surface = AstrionTheme.cardBgAlt,
            onSurface = AstrionTheme.textPrimary,
            surfaceVariant = AstrionTheme.controlBg,
            onSurfaceVariant = AstrionTheme.textSecondary,
            surfaceContainer = AstrionTheme.cardBgAlt,
            outline = AstrionTheme.controlBg,
            error = AstrionTheme.danger,
        ),
        typography = base.copy(
            bodyLarge = base.bodyLarge.copy(fontFamily = f),
            bodyMedium = base.bodyMedium.copy(fontFamily = f),
            bodySmall = base.bodySmall.copy(fontFamily = f),
            labelLarge = base.labelLarge.copy(fontFamily = f),
            labelMedium = base.labelMedium.copy(fontFamily = f),
            titleMedium = base.titleMedium.copy(fontFamily = f),
        ),
        shapes = Shapes(extraSmall = RoundedCornerShape(12.dp)),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides AstrionTheme.textPrimary,
            // Compose's plain default, not MaterialTheme's bodyLarge: that
            // carries a 24sp line height and 0.5sp tracking, which made every
            // label taller (header, lock card, Plex captions) and clipped text.
            LocalTextStyle provides TextStyle.Default.copy(fontFamily = f),
            LocalHapticFeedback provides haptics,
            content = content,
        )
    }
}

/**
 * A short real vibration for every haptic the app asks for. The platform's
 * LongPress pattern on this device is `[0, 1, 20, 21]` — two 1ms pulses — which
 * the small motor barely turns over for.
 */
class StrongHaptics(context: Context) : HapticFeedback {
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private companion object {
        const val PULSE_MS = 18L
    }
}
