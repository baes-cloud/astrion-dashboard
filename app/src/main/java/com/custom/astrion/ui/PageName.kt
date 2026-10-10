package com.custom.astrion.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * Syne 16sp, all caps, 0.28em tracking, `textSecondary` at 60%, centred in a
 * fixed [PageNameHeight] footer; see [baeoWordmark] for the weights. No box,
 * rule or icon.
 *
 * When [name] changes the text crossfades in place: 200 ms out, swap, 200 ms
 * in. That's the only motion; nothing runs between changes. Never used in a
 * dialog or overlay.
 */
@Composable
fun PageName(name: String?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(name) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(name) {
        if (name == shown) return@LaunchedEffect
        fade.animateTo(0f, tween(FADE_MS))
        shown = name
        fade.animateTo(1f, tween(FADE_MS))
    }
    Box(
        modifier = modifier.fillMaxWidth().height(PageNameHeight),
        contentAlignment = Alignment.Center,
    ) {
        val text = shown ?: return@Box
        Text(
            baeoWordmark(text),
            // Tracking also trails the last letter; pushing the text right by
            // one tracking step (0.28em of 16sp ≈ 4.5) centres it optically.
            modifier = Modifier
                .padding(start = 4.5.dp)
                .graphicsLayer { alpha = fade.value },
            color = AstrionTheme.textSecondary.copy(alpha = 0.6f),
            fontFamily = AstrionTheme.headingFont,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            letterSpacing = WordmarkTracking,
            maxLines = 1,
        )
    }
}

/**
 * A Bæo name in caps with the BÆO prefix in Syne Bold and the rest at the
 * text's own weight (Syne Regular), e.g. **BÆO**REMOTE.
 */
fun baeoWordmark(name: String): AnnotatedString {
    val caps = name.uppercase()
    val prefix = if (caps.startsWith(BAEO)) BAEO.length else 0
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(caps.take(prefix)) }
        append(caps.drop(prefix))
    }
}

/** Wordmark letter spacing, shared with the screensaver's. */
val WordmarkTracking = 0.28.em

private const val BAEO = "BÆO"

private const val FADE_MS = 200
