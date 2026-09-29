package com.custom.astrion.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.ha.EntityState

/**
 * One configured alert, from the `alerts` list in dashboard.json:
 *
 *   { "id": "leak", "entity": "binary_sensor.leak", "state": "on",
 *     "for_seconds": 0, "unless": { "entity": "input_boolean.x", "state": "on" },
 *     "severity": "alarm" | "warning" | "info",
 *     "title": "Water leak", "message": "By the washer — since {since}",
 *     "icon": "leak" | "intruder" | "washer" | "door",
 *     "actions": [ { "name": "Lock", "service": "lock.lock", "entity_id": "lock.front" } ] }
 *
 * `state` may also be a list. `{since}` / `{minutes}` in the message are filled
 * from the entity's last_changed.
 */
data class AlertSpec(
    val id: String,
    val entity: String,
    val states: List<String>,
    val forSeconds: Int,
    val unlessEntity: String?,
    val unlessState: String?,
    val severity: String,
    val title: String,
    val message: String?,
    val icon: String?,
    val actions: List<AlertAction>,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(raw: Any?): List<AlertSpec> = (raw as? List<Map<String, Any?>>).orEmpty().mapNotNull { m ->
            val entity = m["entity"] as? String ?: return@mapNotNull null
            val states = when (val s = m["state"]) {
                is String -> listOf(s)
                is List<*> -> s.filterIsInstance<String>()
                else -> listOf("on")
            }
            val unless = m["unless"] as? Map<String, Any?>
            AlertSpec(
                id = m["id"] as? String ?: entity,
                entity = entity,
                states = states,
                forSeconds = (m["for_seconds"] as? Number)?.toInt() ?: 0,
                unlessEntity = unless?.get("entity") as? String,
                unlessState = unless?.get("state") as? String,
                severity = (m["severity"] as? String) ?: "warning",
                title = m["title"] as? String ?: entity,
                message = m["message"] as? String,
                icon = m["icon"] as? String,
                actions = (m["actions"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { a ->
                    val service = a["service"] as? String ?: return@mapNotNull null
                    AlertAction(
                        name = a["name"] as? String ?: service,
                        service = service,
                        entityId = a["entity_id"] as? String,
                        data = (a["data"] as? Map<String, Any?>).orEmpty(),
                    )
                },
            )
        }
    }

    /** Severity as a sort key: alarms first. */
    val rank: Int get() = when (severity) { "alarm" -> 0; "warning" -> 1; else -> 2 }
}

data class AlertAction(
    val name: String,
    val service: String,
    val entityId: String?,
    val data: Map<String, Any?>,
)

/** An alert whose condition currently holds. [token] changes when it re-fires. */
data class ActiveAlert(val spec: AlertSpec, val sinceMs: Long?, val token: String)

/** Which configured alerts are active right now, most severe first. Pure. */
fun activeAlerts(specs: List<AlertSpec>, entities: Map<String, EntityState>, nowMs: Long): List<ActiveAlert> =
    specs.mapNotNull { spec ->
        val e = entities[spec.entity] ?: return@mapNotNull null
        if (e.state !in spec.states) return@mapNotNull null
        if (spec.unlessEntity != null && entities[spec.unlessEntity]?.state == (spec.unlessState ?: "on")) {
            return@mapNotNull null
        }
        val since = e.lastChanged?.let { iso ->
            runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        }
        if (spec.forSeconds > 0 && (since == null || nowMs - since < spec.forSeconds * 1000L)) {
            return@mapNotNull null
        }
        ActiveAlert(spec, since, "${spec.id}@${e.lastChanged}")
    }.sortedBy { it.spec.rank }

private data class Palette(val top: Color, val bottom: Color, val accent: Color, val buttonHi: Color, val buttonLo: Color, val ink: Color)

private fun paletteFor(severity: String): Palette = when (severity) {
    "alarm" -> Palette(Color(0xFF4A1119), Color(0xFF1C070B), Color(0xFFFF5A67), Color(0xFFE2404E), Color(0xFFA81F2D), Color.White)
    "warning" -> Palette(Color(0xFF3F2C0C), Color(0xFF1A1206), Color(0xFFFFB347), Color(0xFFF6C75A), Color(0xFFDC9A22), Color(0xFF3A2605))
    else -> Palette(Color(0xFF27403E), Color(0xFF131B22), Color(0xFF9CCDB8), Color(0xFF9CCDB8), Color(0xFF56736F), Color(0xFF14211F))
}

private fun iconFor(key: String?): ImageVector = when (key) {
    "leak" -> Icons.Filled.WaterDrop
    "intruder" -> Icons.Filled.Security
    "washer" -> Icons.Filled.LocalLaundryService
    "door" -> Icons.Filled.LockOpen
    else -> Icons.Filled.NotificationsActive
}

/**
 * The generic alert popup — same near-full-screen, drawn-in-our-own-window
 * treatment as the work alarm, recoloured by severity. HA owns the state: it
 * is up exactly while the entity condition holds, so clearing it anywhere
 * (phone, HA, another remote) clears it here. "Hide" only hides this copy
 * until the entity changes again.
 */
@Composable
fun AlertOverlay(
    alert: ActiveAlert,
    moreCount: Int,
    nowMs: Long,
    onAction: (AlertAction) -> Unit,
    onHide: () -> Unit,
) {
    val spec = alert.spec
    val p = paletteFor(spec.severity)
    val sinceText = alert.sinceMs?.let {
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it))
    }
    val minutes = alert.sinceMs?.let { ((nowMs - it) / 60_000).coerceAtLeast(0) }
    val message = spec.message
        ?.replace("{since}", sinceText ?: "")
        ?.replace("{minutes}", minutes?.toString() ?: "")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF00A0F14))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            )
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(30.dp))
                .background(Brush.verticalGradient(listOf(p.top, p.bottom)))
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(p.accent.copy(alpha = 0.22f), Color.Transparent),
                            center = Offset(size.width / 2, size.height * 0.25f),
                            radius = size.width * 0.9f,
                        ),
                        radius = size.width * 0.9f,
                        center = Offset(size.width / 2, size.height * 0.25f),
                    )
                }
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when (spec.severity) { "alarm" -> "ALERT"; "warning" -> "HEADS UP"; else -> "FYI" },
                color = p.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.weight(0.6f))
            Glyph(iconFor(spec.icon), p.accent, pulsing = spec.severity == "alarm")
            Spacer(Modifier.height(22.dp))
            Text(
                spec.title,
                color = Color(0xFFEEF2EF),
                fontFamily = AstrionTheme.headingFont, fontSize = 34.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
                lineHeight = 38.sp,
            )
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(message, color = Color(0xFFC8D4CF), fontSize = 16.sp, textAlign = TextAlign.Center)
            }
            if (moreCount > 0) {
                Spacer(Modifier.height(10.dp))
                Text("+$moreCount more", color = Color(0xFF8FA39C), fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))

            spec.actions.forEachIndexed { i, action ->
                val primary = i == 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .then(
                            if (primary) Modifier.background(Brush.horizontalGradient(listOf(p.buttonHi, p.buttonLo)))
                            else Modifier.background(Color(0x1AFFFFFF)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(30.dp))
                        )
                        .tap(onClick = { onAction(action) }),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        action.name,
                        color = if (primary) p.ink else Color(0xFFE8EEEA),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Text(
                "Hide",
                color = Color(0xFFB5C6BF),
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .tap(onClick = onHide)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Glyph(icon: ImageVector, tint: Color, pulsing: Boolean) {
    val t = rememberInfiniteTransition(label = "alert-ripple")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "alert-ripple-phase",
    )
    Box(
        modifier = Modifier
            .size(120.dp)
            .drawBehind {
                if (!pulsing) return@drawBehind
                val base = size.minDimension / 2 * 0.55f
                for (k in 0..1) {
                    val q = (phase + k * 0.5f) % 1f
                    drawCircle(
                        color = tint.copy(alpha = (1f - q) * 0.5f),
                        radius = base * (1f + q * 0.8f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(38.dp))
        }
    }
}
