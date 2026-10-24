package com.custom.astrion.cards.impl

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ui.parseHexColor

/**
 * TV screens on the floor plan that light up while the TV is on: the screen
 * itself fills with a cool glow and a soft pool spills onto the floor round
 * it. Each entry names the TV and the screen's rectangle in percent of the
 * plan (left, top, right, bottom):
 *   "screens": [ { "entity_id": "media_player.the_club_tvv",
 *                  "rect": [23.4, 7.9, 39, 11.5], "color": "#BFE3FF" } ]
 * "also_on" lights it for another source too, e.g. the TV itself on the PC
 * input while the streamer sleeps. Same shape as the IR buttons' "unless";
 * without "attribute" the entity's state is compared:
 *   "also_on": { "entity_id": "media_player.tv", "attribute": "source", "is": "hdmi2" }
 */
@Composable
internal fun ScreenGlowLayer(screens: List<*>, ctx: CardContext) {
    screens.filterIsInstance<Map<*, *>>().forEach { s ->
        val id = s["entity_id"] as? String ?: return@forEach
        val rect = (s["rect"] as? List<*>)?.filterIsInstance<Number>()?.map { it.toFloat() / 100f }
            ?.takeIf { it.size == 4 } ?: return@forEach
        key(id, rect) {
            val e = ctx.entities[id]
            val on = (e != null && !e.isUnavailable && e.state !in TV_OFF) ||
                (s["also_on"] as? Map<*, *>)?.let { alsoOn(it, ctx) } == true
            val level = animateFloatAsState(if (on) 1f else 0f, tween(GLOW_FADE_MS), label = "screen")
            val color = (s["color"] as? String)?.let { parseHexColor(it) } ?: SCREEN_BLUE
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val k = level.value
                        if (k < 0.01f) return@drawBehind
                        val (l, t, r, b) = rect
                        val topLeft = Offset(l * size.width, t * size.height)
                        val screen = Size((r - l) * size.width, (b - t) * size.height)
                        val centre = topLeft + Offset(screen.width / 2, screen.height / 2)
                        val radius = screen.width * 1.1f
                        // The spill first, then the panel on top of it.
                        drawCircle(
                            Brush.radialGradient(
                                0f to color.copy(alpha = 0.55f * k),
                                0.45f to color.copy(alpha = 0.22f * k),
                                1f to color.copy(alpha = 0f),
                                center = centre,
                                radius = radius,
                            ),
                            radius,
                            centre,
                            blendMode = BlendMode.Screen,
                        )
                        drawRoundRect(
                            color.copy(alpha = 0.85f * k),
                            topLeft,
                            screen,
                            CornerRadius(screen.height * 0.15f),
                        )
                    },
            )
        }
    }
}

/** An "also_on" match: the entity's attribute, or its state, is "is" (or one of a list of them). */
private fun alsoOn(c: Map<*, *>, ctx: CardContext): Boolean {
    val e = (c["entity_id"] as? String)?.let { ctx.entities[it] } ?: return false
    if (e.isUnavailable || e.state in TV_OFF) return false
    val actual = (c["attribute"] as? String)?.let { e.attrString(it) } ?: e.state
    return when (val v = c["is"]) {
        is List<*> -> actual in v.map { it.toString() }
        null -> false
        else -> actual == v.toString()
    }
}

private val TV_OFF = setOf("off", "standby", "unavailable", "unknown")
/** A TV's light, like LitRooms' WARM_WHITE: what the screen gives off, not a theme colour. */
private val SCREEN_BLUE = Color(191, 227, 255)
private const val GLOW_FADE_MS = 600
