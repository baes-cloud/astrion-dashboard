package com.custom.astrion.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
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
 * Syne 16sp, all caps, centred in a fixed [PageNameHeight] footer and flanked
 * by left/right edge arrows that cycle continuously through all pages.
 *
 * The "BÆO" prefix is styled in Bold and remains completely static during page
 * navigation; the suffix (REMOTE, VISION, SOUND, LIVING) is spaced across a
 * unified width ([SuffixTargetWidth]) and crossfades in place: 200 ms out,
 * swap, 200 ms in.
 */
@Composable
fun PageName(
    names: List<String?>,
    current: Int,
    onGo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = names.size.coerceAtLeast(1)
    val hasPages = count > 1
    val name = names.getOrNull(current)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PageNameHeight)
            .background(AstrionTheme.pageBg)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EdgeArrow(Glyphs.back, "Previous page", visible = hasPages) {
            onGo((current - 1).mod(count))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(PageNameHeight),
            contentAlignment = Alignment.Center,
        ) {
            Wordmark14(name = name)
        }
        EdgeArrow(Glyphs.fwd, "Next page", visible = hasPages) {
            onGo((current + 1).mod(count))
        }
    }
}

@Composable
private fun EdgeArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    visible: Boolean,
    onClick: () -> Unit,
) {
    // Always laid out, so the name stays put at the ends; slightly faded at 0.34f.
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = PageNameHeight)
            .graphicsLayer { alpha = if (visible) 0.34f else 0f }
            .then(if (visible) Modifier.tap(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = if (visible) label else null, tint = AstrionTheme.textMuted, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun Wordmark14(name: String?) {
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
            // "BÆO" prefix stays static and always visible; only the suffix crossfades.
            suffixFade.animateTo(0f, tween(FADE_MS))
            shownSuffix = newSuffix
            suffixFade.animateTo(1f, tween(FADE_MS))
        } else {
            suffixFade.animateTo(0f, tween(FADE_MS))
            prefixFade.animateTo(0f, tween(FADE_MS))
            shownPrefix = newPrefix
            shownSuffix = newSuffix
            prefixFade.animateTo(1f, tween(FADE_MS))
            suffixFade.animateTo(1f, tween(FADE_MS))
        }
    }

    if (shownPrefix.isEmpty() && shownSuffix.isEmpty()) return

    if (shownPrefix == BAEO) {
        // Unified width wordmark: BÆO is bold and static, and the suffix letters
        // are spaced out across SuffixTargetWidth (105.dp) so that REMOTE, VISION,
        // SOUND, LIVING all occupy the exact same width.
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shownPrefix,
                modifier = Modifier.graphicsLayer { alpha = prefixFade.value },
                color = AstrionTheme.textSecondary.copy(alpha = 0.7f),
                fontFamily = AstrionTheme.headingFont,
                fontWeight = FontWeight.Bold,
                fontSize = AstrionTheme.size.emphasis,
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
                        // An empty first slot, so BÆO sits one letter-gap
                        // from the suffix: SOUND's wide gaps left its S
                        // jammed against the O.
                        Box(Modifier.width(0.dp))
                        shownSuffix.forEach { char ->
                            Text(
                                text = char.toString(),
                                color = AstrionTheme.textSecondary.copy(alpha = 0.7f),
                                fontFamily = AstrionTheme.headingFont,
                                fontWeight = FontWeight.Thin,
                                fontSize = AstrionTheme.size.emphasis,
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
                        fontWeight = FontWeight.Thin,
                        fontSize = AstrionTheme.size.emphasis,
                        maxLines = 1,
                    )
                }
            }
        }
    } else {
        Text(
            text = shownSuffix,
            modifier = Modifier
                .padding(start = 5.dp)
                .graphicsLayer { alpha = suffixFade.value },
            color = AstrionTheme.textSecondary.copy(alpha = 0.7f),
            fontFamily = AstrionTheme.headingFont,
            fontWeight = FontWeight.Thin,
            fontSize = AstrionTheme.size.emphasis,
            letterSpacing = WordmarkTracking,
            maxLines = 1,
        )
    }
}

/** Fixed width for the suffix and the gap before it (REMOTE's 16sp Syne width plus one gap). */
val SuffixTargetWidth = 112.dp

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
