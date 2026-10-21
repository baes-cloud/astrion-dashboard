package com.custom.astrion.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Height of the nameplate. The same on every page, so the name never moves. */
val PageNameHeight = 46.dp

/**
 * Lets a card stand in its own name for the page's, e.g. the Media page's
 * swipe_stack, whose Link tab reads BÆOLINK while the others read BÆOSOUND.
 * Null outside a page; a card that sets it owns it for as long as it's shown.
 */
val LocalPageNameOverride = compositionLocalOf<MutableState<String?>?> { null }

/**
 * The nameplate: the page's Bæo name leads, centred, and navigation is a
 * whisper either side of it. Pages before this one sit as faint dots on the
 * name's left, pages after it on its right; an arrow at each edge shows only
 * when there is a page that way. Tap a dot or an arrow, or swipe the page.
 *
 * BÆO never moves: every suffix (REMOTE, VISION, SOUND, LIVING) is spread
 * across the same [SuffixWidth], all of them stacked in one place, and a
 * page change slides the old one out and the new one in from the side the
 * page came from (opacity and translation only, cheap on this GPU).
 */
@Composable
fun PageName(
    names: List<String?>,
    current: Int,
    onGo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = AstrionTheme.textSecondary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PageNameHeight)
            .background(AstrionTheme.pageBg)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EdgeArrow(Glyphs.back, "Previous page", visible = current > 0) { onGo(current - 1) }
        Dots(range = 0 until current, names = names, onGo = onGo, modifier = Modifier.weight(1f))
        Wordmark(names, current, ink)
        Dots(range = current + 1 until names.size, names = names, onGo = onGo, modifier = Modifier.weight(1f))
        EdgeArrow(Glyphs.fwd, "Next page", visible = current < names.size - 1) { onGo(current + 1) }
    }
}

@Composable
private fun EdgeArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    visible: Boolean,
    onClick: () -> Unit,
) {
    // Always laid out, so the name stays put at the ends; just not shown or tappable.
    Box(
        modifier = Modifier
            .size(width = 38.dp, height = PageNameHeight)
            .graphicsLayer { alpha = if (visible) 0.5f else 0f }
            .then(if (visible) Modifier.tap(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = if (visible) label else null, tint = AstrionTheme.textMuted, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun Dots(range: IntRange, names: List<String?>, onGo: (Int) -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in range) {
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .fillMaxHeight()
                    .tap { onGo(i) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(3.dp)
                        .graphicsLayer { alpha = 0.4f }
                        .clip(CircleShape)
                        .background(AstrionTheme.textMuted),
                )
            }
        }
    }
}

@Composable
private fun Wordmark(names: List<String?>, current: Int, ink: androidx.compose.ui.graphics.Color) {
    val caps = names.map { it?.uppercase().orEmpty() }
    val allBaeo = caps.isNotEmpty() && caps.all { it.startsWith(BAEO) }
    Row(
        modifier = Modifier.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (allBaeo) {
            Text(
                BAEO,
                color = ink,
                fontFamily = AstrionTheme.bodyFont,
                fontWeight = FontWeight.ExtraBold,
                fontSize = WordmarkSize,
                letterSpacing = WordmarkTracking,
                maxLines = 1,
            )
        }
        Box(Modifier.width(if (allBaeo) SuffixWidth else SuffixWidth + 40.dp), contentAlignment = Alignment.CenterStart) {
            caps.forEachIndexed { i, name ->
                val suffix = if (allBaeo) name.removePrefix(BAEO) else name
                Suffix(suffix, i - current, ink)
            }
        }
    }
}

/** One page's suffix: [offset] is its place relative to the page on screen (0 = this one). */
@Composable
private fun Suffix(text: String, offset: Int, ink: androidx.compose.ui.graphics.Color) {
    val slide = with(LocalDensity.current) { 9.dp.toPx() }
    val alpha by animateFloatAsState(if (offset == 0) 1f else 0f, tween(SLIDE_MS), label = "suffixAlpha")
    val dx by animateFloatAsState(
        when {
            offset < 0 -> -slide
            offset > 0 -> slide
            else -> 0f
        },
        tween(SLIDE_MS),
        label = "suffixSlide",
    )
    if (alpha == 0f && offset != 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                translationX = dx
            },
        horizontalArrangement = if (text.length > 1) Arrangement.SpaceBetween else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        text.forEach { c ->
            Text(
                c.toString(),
                color = ink,
                fontFamily = AstrionTheme.bodyFont,
                fontWeight = FontWeight.Medium,
                fontSize = WordmarkSize,
                maxLines = 1,
            )
        }
    }
}

/** Every page's suffix spans this width, so BÆO sits in the same place on every page. */
val SuffixWidth = 80.dp

private val WordmarkSize = 14.sp

/**
 * A Bæo name in caps with the BÆO prefix in Manrope ExtraBold and the rest
 * at Medium, e.g. **BÆO**REMOTE.
 */
fun baeoWordmark(name: String): AnnotatedString {
    val caps = name.uppercase()
    val prefix = if (caps.startsWith(BAEO)) BAEO.length else 0
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(caps.take(prefix)) }
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(caps.drop(prefix)) }
    }
}

/** Wordmark letter spacing. */
val WordmarkTracking = 0.34.em

private const val BAEO = "BÆO"

private const val SLIDE_MS = 260
