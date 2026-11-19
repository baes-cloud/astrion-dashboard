package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.Glyphs
import com.custom.astrion.ui.components.BaeoSectionHeader
import com.custom.astrion.ui.tap

/**
 * Pure typographic section heading (`BaeoSectionHeader`) with an optional row
 * of borderless matte preset pills (e.g. `Open | Sheer | Shut` above blinds).
 *
 * Config:
 *   { "type": "section", "options": {
 *       "title": "Blinds",
 *       "presets": [
 *         { "name": "Open", "icon": "sun", "tone": "accent",
 *           "match": [{ "entity_id": "cover.blinds", "invert_position": true, "min": 80 },
 *                     { "entity_id": "cover.sheer_blinds", "min": 80 }],
 *           "calls": [{ "service": "cover.set_cover_position", "entity_id": "cover.blinds", "data": { "position": 0 } },
 *                     { "service": "cover.set_cover_position", "entity_id": "cover.sheer_blinds", "data": { "position": 100 } }] }
 *       ]
 *   } }
 */
class SectionCard : CardRenderer {
    override val type = "section"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val title = config.string("title") ?: return
        val presets = (config.options["presets"] as? List<Map<String, Any?>>).orEmpty()
        if (presets.isEmpty()) {
            BaeoSectionHeader(title)
            return
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BaeoSectionHeader(title)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presets.forEach { preset ->
                    val label = preset["name"] as? String ?: return@forEach
                    val icon = presetIcon(preset["icon"] as? String)
                    val tone = preset["tone"] as? String
                    val active = matchesPreset(preset, ctx)
                    val calls = parsePresetCalls(preset)
                    val bg = when {
                        !active -> AstrionTheme.cardBg
                        tone == "blush" -> AstrionTheme.blushContainer
                        tone == "ochre" -> AstrionTheme.ochreSurface
                        else -> AstrionTheme.accentStrong
                    }
                    val ink = when {
                        !active -> AstrionTheme.textSecondary
                        tone == "blush" -> AstrionTheme.blush
                        tone == "ochre" -> AstrionTheme.ochreInk
                        else -> AstrionTheme.colors.onPrimary
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(bg)
                            .tap(enabled = ctx.connected && calls.isNotEmpty()) {
                                calls.forEach { ctx.client.callService(it) }
                            }
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (icon != null) {
                            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            text = label,
                            color = ink,
                            fontSize = AstrionTheme.size.label,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    private fun presetIcon(name: String?): ImageVector? = when (name) {
        "sun", "open" -> Glyphs.sun
        "sheer" -> Glyphs.sheer
        "moon", "shut", "closed" -> Glyphs.moon
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun matchesPreset(preset: Map<String, Any?>, ctx: CardContext): Boolean {
        val rules = (preset["match"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
        if (rules.isEmpty()) return false
        return rules.all { rule ->
            val id = rule["entity_id"] as? String ?: return@all false
            val e = ctx.entities[id] ?: return@all false
            val invert = rule["invert_position"] as? Boolean ?: false
            val rawPos = e.attrInt("current_position")
            val pos = rawPos?.let { if (invert) 100 - it else it }
                ?: if ((e.state == "open") != invert) 100 else 0
            val min = (rule["min"] as? Number)?.toInt() ?: 0
            val max = (rule["max"] as? Number)?.toInt() ?: 100
            pos in min..max
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePresetCalls(preset: Map<String, Any?>): List<ServiceCall> {
        val list = (preset["calls"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
        return list.mapNotNull { m ->
            val svc = m["service"] as? String ?: return@mapNotNull null
            val data = (m["data"] as? Map<String, Any?>).orEmpty()
            ServiceCall.fromConfig(svc, m["entity_id"] as? String, data)
        }
    }
}
