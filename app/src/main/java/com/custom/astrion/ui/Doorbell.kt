package com.custom.astrion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Doorbell
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import kotlinx.coroutines.delay

/**
 * The doorbell popup, from the `doorbell` block in dashboard.json:
 *
 *   "doorbell": { "event": "event.intercom_doorbell", "ring": ["ring"],
 *                 "camera": "camera.intercom_entrance", "timeout_seconds": 90,
 *                 "let_in": { "service": "script.turn_on", "entity_id": "script.intercom_let_in" } }
 *
 * HA event entities hold the time of their last event as their state and its
 * kind in `event_type`, so this shows while the last event is one of [ring]
 * and is under [timeoutSeconds] old: a newer `call_ended`, or the timeout,
 * takes it down everywhere. "Hide" only hides this copy until the next ring.
 */
data class DoorbellSpec(
    val event: String,
    val ring: List<String>,
    val camera: String?,
    val timeoutSeconds: Int,
    val letIn: AlertAction?,
) {
    val entities: Set<String> get() = setOfNotNull(event, camera)

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(raw: Any?): DoorbellSpec? {
            val m = raw as? Map<String, Any?> ?: return null
            val event = m["event"] as? String ?: return null
            val letIn = (m["let_in"] as? Map<String, Any?>)?.let { a ->
                val service = a["service"] as? String ?: return@let null
                AlertAction(
                    name = a["name"] as? String ?: "Let in",
                    service = service,
                    entityId = a["entity_id"] as? String,
                    data = (a["data"] as? Map<String, Any?>).orEmpty(),
                )
            }
            return DoorbellSpec(
                event = event,
                ring = (m["ring"] as? List<*>)?.filterIsInstance<String>() ?: listOf("ring"),
                camera = m["camera"] as? String,
                timeoutSeconds = (m["timeout_seconds"] as? Number)?.toInt() ?: 90,
                letIn = letIn,
            )
        }
    }
}

/** A ring on screen. [token] is the ring's time, so a new ring shows again after Hide. */
data class DoorbellRing(val spec: DoorbellSpec, val atMs: Long, val token: String) {
    val untilMs: Long get() = atMs + spec.timeoutSeconds * 1000L
}

/** The ring that should be up now, if any. Pure. */
fun doorbellRing(spec: DoorbellSpec?, entities: Map<String, EntityState>, nowMs: Long): DoorbellRing? {
    spec ?: return null
    val e = entities[spec.event] ?: return null
    if (e.isUnavailable || e.attrString("event_type") !in spec.ring) return null
    val at = e.lastChangedMs ?: return null
    if (nowMs - at > spec.timeoutSeconds * 1000L) return null
    return DoorbellRing(spec, at, "${spec.event}@$at")
}

/**
 * Who's at the door: the camera's snapshot, when it rang, and Let in. Same
 * full-screen treatment as the alerts. The snapshot reloads when HA takes a
 * new one (the camera's `snapshot_at`) and every few seconds while up.
 */
@Composable
fun DoorbellOverlay(
    ring: DoorbellRing,
    client: HaClient,
    camera: EntityState?,
    onLetIn: (AlertAction) -> Unit,
    onHide: () -> Unit,
) {
    val picture = camera?.attrString("entity_picture")
    var snapshot by remember(ring.token) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(ring.token, picture, camera?.attrString("snapshot_at")) {
        picture ?: return@LaunchedEffect
        while (true) {
            client.fetchBytes(picture)?.let { bytes -> decodeSampled(bytes, SNAPSHOT_PX)?.let { snapshot = it } }
            delay(SNAPSHOT_REFRESH_MS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstrionTheme.colors.scrimDark)
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
                .background(Brush.verticalGradient(listOf(AstrionTheme.colors.accentContainer, AstrionTheme.colors.pinnedTopBg)))
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "DOORBELL",
                color = AstrionTheme.colors.accentBright,
                fontSize = AstrionTheme.size.small,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(AstrionTheme.colors.void),
                contentAlignment = Alignment.Center,
            ) {
                val shot = snapshot
                if (shot != null) {
                    Image(shot, contentDescription = "Entrance", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Filled.Doorbell, contentDescription = null, tint = AstrionTheme.colors.accentBright)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Someone's at the door",
                color = AstrionTheme.colors.onSurface,
                fontFamily = AstrionTheme.headingFont, fontSize = AstrionTheme.size.displayXl,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
                lineHeight = 38.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Rang at ${Time.format(ring.atMs, "h:mm a")}",
                color = AstrionTheme.colors.textSoft,
                fontSize = AstrionTheme.size.emphasis,
            )
            Spacer(Modifier.weight(1f))

            ring.spec.letIn?.let { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(AstrionTheme.accent)
                        .tap(onClick = { onLetIn(action) }),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        action.name,
                        color = AstrionTheme.colors.pinnedTopBg,
                        fontSize = AstrionTheme.size.heading,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            Text(
                "Hide",
                color = AstrionTheme.colors.textSoft,
                fontSize = AstrionTheme.size.bodyLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .tap(onClick = onHide)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

private const val SNAPSHOT_PX = 640
private const val SNAPSHOT_REFRESH_MS = 5_000L
