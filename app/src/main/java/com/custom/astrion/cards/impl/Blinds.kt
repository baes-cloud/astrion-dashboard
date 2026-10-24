package com.custom.astrion.cards.impl

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ui.parseHexColor

/**
 * Blinds on the floor plan: fabric over the window's "rect" (left, top,
 * right, bottom in percent of the plan) that comes down from the top as the
 * blind closes, so up shows the window, down covers it and anything between
 * covers that much of it. A shade lighter than the wall so it reads as a
 * blind without shouting. "invert_position" for a motor that reports
 * backwards (same as the cover tile); "style": "sheer" is see-through.
 *   "blinds": [ { "entity_id": "cover.blinds", "rect": [2, 7.5, 7.5, 41.5],
 *                 "invert_position": true },
 *               { "entity_id": "cover.sheer", "rect": [2, 7.5, 7.5, 41.5], "style": "sheer" } ]
 * An unavailable blind draws nothing.
 */
@Composable
internal fun BlindsLayer(blinds: List<*>, ctx: CardContext) {
    blinds.filterIsInstance<Map<*, *>>().forEach { b ->
        val id = b["entity_id"] as? String ?: return@forEach
        val rect = (b["rect"] as? List<*>)?.filterIsInstance<Number>()?.map { it.toFloat() / 100f }
            ?.takeIf { it.size == 4 } ?: return@forEach
        key(id, rect) {
            val closed = ctx.entities[id]?.let { closedFraction(it, b["invert_position"] as? Boolean ?: false) }
            val k = animateFloatAsState(closed ?: 0f, tween(BLIND_MS), label = "blind")
            val sheer = b["style"] == "sheer"
            val color = parseHexColor(b["color"] as? String) ?: if (sheer) SHEER else FABRIC
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val f = k.value
                        if (closed == null || f < 0.01f) return@drawBehind
                        val (l, t, r, bottom) = rect
                        val left = l * size.width
                        val top = t * size.height
                        val width = (r - l) * size.width
                        val drop = (bottom - t) * size.height * f
                        drawRect(color.copy(alpha = if (sheer) 0.5f else 0.9f), Offset(left, top), Size(width, drop))
                        // The bottom bar across the end of the fabric.
                        drawRect(BAR.copy(alpha = if (sheer) 0.35f else 0.6f), Offset(left, top + drop - BAR_H.toPx()), Size(width, BAR_H.toPx()))
                    },
            )
        }
    }
}

/** 0 open to 1 closed, from the position if HA has one; null when unavailable or unknown. */
private fun closedFraction(e: EntityState, invert: Boolean): Float? {
    if (e.isUnavailable) return null
    e.attrInt("current_position")?.let { raw ->
        val open = if (invert) 100 - raw else raw
        return (1f - open / 100f).coerceIn(0f, 1f)
    }
    return when (e.state) {
        "open" -> if (invert) 1f else 0f
        "closed" -> if (invert) 0f else 1f
        else -> null
    }
}

/** A shade lighter than the plan's walls (about 205, 197, 196), so a blind blends in but still shows. */
private val FABRIC = Color(226, 220, 217)
private val SHEER = Color(240, 236, 233)
private val BAR = Color(120, 112, 110)
private val BAR_H = 1.5.dp
private const val BLIND_MS = 600
