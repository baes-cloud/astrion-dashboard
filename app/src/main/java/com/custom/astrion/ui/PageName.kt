package com.custom.astrion.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Height of the device-name footer. The same on every page, so the name never moves. */
val PageNameHeight = 44.dp

/**
 * Lets a card stand in its own name for the page's, e.g. the Media page's
 * swipe_stack, whose Link tab reads BÆOLINK while the others read BÆOSOUND.
 * Null outside a page; a card that sets it owns it for as long as it's shown.
 */
val LocalPageNameOverride = compositionLocalOf<MutableState<String?>?> { null }

/**
 * The Bæo device name for the page on screen (BÆOREMOTE, BÆOVISION, …):
 * Syne 16sp, all caps, 0.318em tracking, `textSecondary` at 70%, centred in a
 * fixed [PageNameHeight] footer.
 *
 * The "BÆO" prefix is styled in Bold and remains completely static during page
 * navigation; the suffix (e.g. REMOTE, VISION, SOUND) crossfades in place:
 * 200 ms out, swap, 200 ms in.
 */
@Composable
fun PageName(name: String?, modifier: Modifier = Modifier) {
    val targetCaps = name?.uppercase() ?: ""
    val targetPrefix = if (targetCaps.startsWith(BAEO)) BAEO else ""
    val targetSuffix = if (targetPrefix.isNotEmpty()) targetCaps.removePrefix(BAEO) else targetCaps

    var shownPrefix by remember { mutableStateOf(targetPrefix) }
    var shownSuffix by remember { mutableStateOf(targetSuffix) }
    val suffixFade = remember { Animatable(1f) }
    val prefixFade = remember { Animatable(1f) }

    LaunchedEffect(name) {
        val caps = name?.uppercase() ?: ""
        val newPrefix = if (caps.startsWith(BAEO)) BAEO else ""
        val newSuffix = if (newPrefix.isNotEmpty()) caps.removePrefix(BAEO) else caps

        if (newPrefix == shownPrefix && newSuffix == shownSuffix) return@LaunchedEffect

        if (newPrefix == shownPrefix && shownPrefix.isNotEmpty()) {
            // "BÆO" prefix is static! Only the suffix crossfades.
            suffixFade.animateTo(0f, tween(FADE_MS))
            shownSuffix = newSuffix
            suffixFade.animateTo(1f, tween(FADE_MS))
        } else {
            // Entire name changed or non-BÆO name
            suffixFade.animateTo(0f, tween(FADE_MS))
            prefixFade.animateTo(0f, tween(FADE_MS))
            shownPrefix = newPrefix
            shownSuffix = newSuffix
            prefixFade.animateTo(1f, tween(FADE_MS))
            suffixFade.animateTo(1f, tween(FADE_MS))
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PageNameHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (shownPrefix.isEmpty() && shownSuffix.isEmpty()) return@Box

        if (shownPrefix == BAEO) {
            // Unified width wordmark: BÆO is bold, and the suffix letters are
            // spaced out across SuffixTargetWidth (105.dp) so that REMOTE, VISION,
            // SOUND, LIVING all end up with the exact same width and boundaries.
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = shownPrefix,
                    modifier = Modifier.graphicsLayer { alpha = prefixFade.value },
                    color = AstrionTheme.textSecondary.copy(alpha = 0.7f),
                    fontFamily = AstrionTheme.headingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = WordmarkTracking,
                    maxLines = 1,
                )
                if (shownSuffix.isNotEmpty()) {
                    if (shownSuffix.length > 1) {
                        Row(
                            modifier = Modifier
                                .width(SuffixTargetWidth)
                                .graphicsLayer { alpha = suffixFade.value },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            shownSuffix.forEach { char ->
                                Text(
                                    text = char.toString(),
                                    color = AstrionTheme.textSecondary.copy(alpha = 0.7f),
                                    fontFamily = AstrionTheme.headingFont,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = shownSuffix,
                            modifier = Modifier.graphicsLayer { alpha = suffixFade.value },
                            color = AstrionTheme.textSecondary.copy(alpha = 0.7f),
                            fontFamily = AstrionTheme.headingFont,
                            fontWeight = FontWeight.Normal,
                            fontSize = 16.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        } else {
            // Generic fallback for non-BÆO titles: centered
            Text(
                text = shownSuffix,
                modifier = Modifier
                    .padding(start = 5.dp)
                    .graphicsLayer { alpha = suffixFade.value },
                color = AstrionTheme.textSecondary.copy(alpha = 0.7f),
                fontFamily = AstrionTheme.headingFont,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                letterSpacing = WordmarkTracking,
                maxLines = 1,
            )
        }
    }
}

/** Fixed width for the suffix (matches natural 16sp Syne width of REMOTE). */
val SuffixTargetWidth = 105.dp

/**
 * A Bæo name in caps with the BÆO prefix in Syne Bold and the rest at the
 * text's own weight (Syne Regular / Normal), e.g. **BÆO**REMOTE.
 */
fun baeoWordmark(name: String): AnnotatedString {
    val caps = name.uppercase()
    val prefix = if (caps.startsWith(BAEO)) BAEO.length else 0
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(caps.take(prefix)) }
        withStyle(SpanStyle(fontWeight = FontWeight.Normal)) { append(caps.drop(prefix)) }
    }
}

/** Wordmark letter spacing (7px at 22px = 0.318em). */
val WordmarkTracking = 0.318.em

private const val BAEO = "BÆO"

private const val FADE_MS = 200

