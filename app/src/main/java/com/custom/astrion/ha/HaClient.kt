package com.custom.astrion.ha

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal, dependency-light Home Assistant WebSocket client.
 *
 * This speaks the *standard* HA websocket API — exactly the same handshake and
 * commands the stock HaRemote app uses (confirmed by decompiling it):
 *
 *   1. Server sends   { type: "auth_required" }
 *   2. We send        { type: "auth", access_token: "<long-lived token>" }
 *   3. Server sends   { type: "auth_ok" }  (or "auth_invalid")
 *   4. We             subscribe_entities: the first event carries every
 *                     entity's state, later ones compact per-entity diffs
 *   6. Heartbeat via  { type: "ping" } / { type: "pong" }
 *
 * Because it's the stock protocol, this app needs nothing from Sanytron's
 * cloud or their custom integration to function — only a reachable HA instance
 * and a long-lived access token.
 *
 * Deliberately small: one socket, a StateFlow of the entity map, a StateFlow of
 * connection status, and callService(). No Sanytron `astrion/...` events are used
 * here (those are only needed if you later want the local IR-blaster path).
 */
class HaClient(
    private val baseUrl: String,   // e.g. "http://YOUR_HA_IP:8123" or "https://ha.example.com"
    private val token: String,     // long-lived access token
) {
    companion object {
        private const val TAG = "HaClient"
        private const val PING_INTERVAL_MS = 30_000L
        private const val PUBLISH_INTERVAL_MS = 120L
        /** How long a request waits for the socket to (re)authenticate. */
        private const val CONNECT_WAIT_MS = 15_000L
        private const val RECONNECT_MIN_MS = 3_000L
        private const val RECONNECT_MAX_MS = 60_000L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicInteger(1)

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Separate, sanely-timed client for one-shot HTTP GETs (album art).
    private val imageHttp = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null

    /**
     * Bumped on every (re)connect. Streaming handler ids (assist audio) are
     * per-connection, so anything holding one checks this to know its id is
     * stale — otherwise it keeps pushing frames HA can't route.
     */
    @Volatile var epoch = 0
        private set

    /** Outstanding request/response commands (e.g. browse_media), keyed by id. */
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    /**
     * Long-running commands that stream many `event` messages under one id
     * (assist_pipeline/run), keyed by id. Checked before the state_changed
     * path in onEvent so pipeline events never reach the entity store.
     */
    private val eventHandlers = ConcurrentHashMap<Int, (JsonObject) -> Unit>()

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _entities = MutableStateFlow<EntityMap>(emptyMap())
    /** Live map of every entity's current state. Cards observe this. */
    val entities: StateFlow<EntityMap> = _entities.asStateFlow()

    // Working store updated on every event; published to _entities at most once
    // per PUBLISH_INTERVAL_MS so a chatty sensor (e.g. mmWave radar at several
    // Hz) can't force the whole UI to repaint faster than the SoC can handle.
    private val entityStore = ConcurrentHashMap<String, EntityState>()
    @Volatile private var entitiesDirty = false
    @Volatile private var publisherStarted = false

    /**
     * One snapshot state per entity, for [entityState]. A composable that
     * reads one of these recomposes only when THAT entity changes, whereas
     * reading [entities] recomposes on any change anywhere in HA.
     */
    private val entityStates = ConcurrentHashMap<String, MutableState<EntityState?>>()
    /** Entities changed since the last publish, for the per-entity states. */
    private val changedIds = ConcurrentHashMap.newKeySet<String>()

    /** Request id of the live subscribe_entities, whose events are diffs. */
    @Volatile private var entitiesSubId = -1
    /** True until the current subscription's first (full-snapshot) event. */
    @Volatile private var awaitingSeed = false

    private var reconnectJob: Job? = null
    /** Consecutive failed connects, for backoff; reset on auth_ok. */
    @Volatile private var reconnectAttempts = 0

    // ---- public API ---------------------------------------------------------

    fun connect() {
        epoch++
        reconnectJob?.cancel()
        // Retire any previous socket first; the listener ignores callbacks
        // from sockets other than the current one, so this can't loop.
        socket?.cancel()
        _connection.value = ConnectionState.CONNECTING
        val wsUrl = toWebSocketUrl(baseUrl)
        Log.i(TAG, "Connecting to $wsUrl")
        val req = Request.Builder().url(wsUrl).build()
        socket = http.newWebSocket(req, listener)
    }

    fun disconnect() {
        reconnectJob?.cancel()
        _connection.value = ConnectionState.DISCONNECTED
        socket?.close(1000, "client closing")
        socket = null
    }

    /**
     * The network just came (back) up: if we're waiting out a reconnect
     * backoff, skip the wait. A live connection is left alone.
     */
    fun onNetworkAvailable() {
        when (_connection.value) {
            ConnectionState.ERROR, ConnectionState.AUTH_FAILED -> {
                reconnectAttempts = 0
                connect()
            }
            else -> {}
        }
    }

    /**
     * Live state of one entity, as Compose state. Reading its value inside a
     * composable subscribes that scope to this entity alone.
     */
    fun entityState(entityId: String): State<EntityState?> =
        entityStates.getOrPut(entityId) { mutableStateOf(entityStore[entityId]) }

    /** Fire a HA service call, e.g. light.toggle on light.kitchen. */
    fun callService(call: ServiceCall) {
        val target = buildJsonObject {
            call.entityId?.let { put("entity_id", it) }
        }
        val msg = buildJsonObject {
            put("id", idCounter.getAndIncrement())
            put("type", "call_service")
            put("domain", call.domain)
            put("service", call.service)
            if (call.data.isNotEmpty()) {
                put("service_data", JsonObject(call.data))
            }
            put("target", target)
        }
        send(msg)
    }

    /** Convenience helper mirroring the common toggle pattern. */
    fun toggle(entityId: String) {
        val domain = entityId.substringBefore('.')
        callService(ServiceCall(domain = domain, service = "toggle", entityId = entityId))
    }

    /**
     * Fetch an image (e.g. a media_player `entity_picture`) as an ImageBitmap.
     * `path` may be absolute or an HA-relative path like /api/media_player_proxy/…;
     * the bearer token is attached so proxied/authenticated art loads too.
     */
    suspend fun fetchBitmap(path: String): ImageBitmap? =
        fetchBytes(path)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }

    /** The raw bytes behind [fetchBitmap] (e.g. to cache before decoding). */
    suspend fun fetchBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            imageHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchBytes failed for $path", e)
            null
        }
    }

    /**
     * Browse a media_player's library via the standard `media_player/browse_media`
     * command. Returns the `result` object (title + children), or null on timeout.
     * Pass a null contentId/type to browse the root.
     */
    suspend fun browseMedia(
        entityId: String,
        contentId: String? = null,
        contentType: String? = null,
    ): JsonObject? {
        if (!awaitConnected()) return null
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val msg = buildJsonObject {
            put("id", id)
            put("type", "media_player/browse_media")
            put("entity_id", entityId)
            contentId?.let { put("media_content_id", it) }
            contentType?.let { put("media_content_type", it) }
        }
        send(msg)
        val reply = withTimeoutOrNull(8_000) { deferred.await() }
        pending.remove(id)
        return reply?.get("result") as? JsonObject
    }

    /**
     * Fetch a weather forecast via `weather.get_forecasts` (modern HA no longer
     * exposes `forecast` as an attribute). Returns the forecast array
     * (each item: datetime, condition, temperature, templow), or null.
     */
    suspend fun getForecast(entityId: String, forecastType: String = "daily"): JsonArray? {
        if (!awaitConnected()) return null
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val msg = buildJsonObject {
            put("id", id)
            put("type", "call_service")
            put("domain", "weather")
            put("service", "get_forecasts")
            put("service_data", buildJsonObject { put("type", forecastType) })
            put("target", buildJsonObject { put("entity_id", entityId) })
            put("return_response", true)
        }
        send(msg)
        val reply = withTimeoutOrNull(8_000) { deferred.await() }
        pending.remove(id)
        val response = reply?.get("result")?.jsonObject?.get("response")?.jsonObject ?: return null
        return response[entityId]?.jsonObject?.get("forecast") as? JsonArray
    }

    /**
     * Call a service that returns data (`return_response`), e.g.
     * `music_assistant.get_library`. Returns the `response` object, or null on
     * timeout / error.
     */
    suspend fun callServiceForResponse(
        domain: String,
        service: String,
        data: JsonObject = JsonObject(emptyMap()),
        entityId: String? = null,
        timeoutMs: Long = 10_000,
    ): JsonObject? {
        if (!awaitConnected()) return null
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val msg = buildJsonObject {
            put("id", id)
            put("type", "call_service")
            put("domain", domain)
            put("service", service)
            put("service_data", data)
            entityId?.let { put("target", buildJsonObject { put("entity_id", it) }) }
            put("return_response", true)
        }
        send(msg)
        val reply = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(id)
        return reply?.get("result")?.jsonObject?.get("response")?.jsonObject
    }

    /**
     * Start a streaming command (e.g. `assist_pipeline/run`) whose progress
     * arrives as a series of `event` messages sharing one request id.
     *
     * Returns the id so the caller can [endSubscription] when finished, or
     * null if the socket isn't up. `onEvent` receives the inner `event` object.
     */
    fun startSubscription(build: JsonObjectBuilder.() -> Unit, onEvent: (JsonObject) -> Unit): Int? {
        if (socket == null || _connection.value != ConnectionState.CONNECTED) return null
        val id = idCounter.getAndIncrement()
        eventHandlers[id] = onEvent
        val msg = buildJsonObject {
            put("id", id)
            build()
        }
        send(msg)
        return id
    }

    /** Stop routing events for a streaming command started by [startSubscription]. */
    fun endSubscription(id: Int) {
        eventHandlers.remove(id)
    }

    /**
     * End a long-lived subscription (e.g. `rmm/stream`) on the server too, so
     * HA stops pushing frames nobody reads. Safe on a dead socket: the
     * subscription died with it.
     */
    fun unsubscribe(id: Int) {
        eventHandlers.remove(id)
        if (socket == null || _connection.value != ConnectionState.CONNECTED) return
        send(buildJsonObject {
            put("id", idCounter.getAndIncrement())
            put("type", "unsubscribe_events")
            put("subscription", id)
        })
    }

    /**
     * Send a raw binary frame. HA's Assist pipeline expects audio as
     * `[handler_id byte] + [raw PCM]`; a frame containing only the handler
     * byte signals end-of-audio.
     */
    fun sendAudioChunk(handlerId: Int, pcm: ByteArray, length: Int, forEpoch: Int = epoch): Boolean {
        if (forEpoch != epoch) return false
        val sock = socket ?: return false
        val frame = ByteArray(length + 1)
        frame[0] = handlerId.toByte()
        System.arraycopy(pcm, 0, frame, 1, length)
        return sock.send(frame.toByteString(0, frame.size))
    }

    /** Absolute URL for an HA-relative path, with the bearer token attached. */
    fun authedUrl(path: String): String =
        if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path

    /** The long-lived token, for callers that must build their own authed request. */
    fun bearerToken(): String = token

    /** Play a specific media item on a player. */
    fun playMedia(entityId: String, contentId: String, contentType: String) {
        callService(
            ServiceCall.of(
                "media_player", "play_media", entityId,
                "media_content_id" to contentId,
                "media_content_type" to contentType,
            )
        )
    }

    // ---- internals ----------------------------------------------------------

    /**
     * Wait (bounded) until the socket is authenticated. Anything sent before
     * auth_ok is read by HA as a malformed auth message, and HA answers by
     * dropping the connection — so requests made while (re)connecting, e.g. a
     * card loading at startup, must wait rather than go out early.
     */
    private suspend fun awaitConnected(): Boolean =
        _connection.value == ConnectionState.CONNECTED ||
            withTimeoutOrNull(CONNECT_WAIT_MS) {
                connection.first { it == ConnectionState.CONNECTED }
            } != null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            Log.i(TAG, "Socket open, waiting for auth_required")
            _connection.value = ConnectionState.AUTHENTICATING
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            try {
                val obj = json.parseToJsonElement(text).jsonObject
                when (obj["type"]?.jsonPrimitive?.content) {
                    "auth_required" -> sendAuth()
                    "auth_ok" -> onAuthOk()
                    "auth_invalid" -> {
                        Log.w(TAG, "auth_invalid: ${obj["message"]}")
                        _connection.value = ConnectionState.AUTH_FAILED
                    }
                    "result" -> {
                        // Route replies to the command awaiting this id, if any.
                        val id = obj["id"]?.jsonPrimitive?.intOrNull
                        id?.let { pending.remove(it) }?.complete(obj)
                    }
                    "event" -> onEvent(obj)
                    "pong" -> { /* heartbeat ok */ }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onMessage parse error", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            Log.e(TAG, "Socket failure", t)
            _connection.value = ConnectionState.ERROR
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            Log.w(TAG, "Socket closed $code $reason")
            if (_connection.value != ConnectionState.DISCONNECTED) {
                // HA closed us (e.g. a restart): scheduleReconnect only acts on ERROR.
                _connection.value = ConnectionState.ERROR
                scheduleReconnect()
            }
        }
    }

    private fun sendAuth() {
        val msg = buildJsonObject {
            put("type", "auth")
            put("access_token", token)
        }
        // NOTE: auth message must NOT include an id (HA rejects it otherwise).
        socket?.send(msg.toString())
    }

    private fun onAuthOk() {
        Log.i(TAG, "Authenticated")
        reconnectAttempts = 0
        _connection.value = ConnectionState.CONNECTED
        startPublisher()
        subscribeEntities()
        startHeartbeat()
    }

    /** Coalesce entity updates: publish the store to the StateFlow at a bounded rate. */
    private fun startPublisher() {
        if (publisherStarted) return
        publisherStarted = true
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(PUBLISH_INTERVAL_MS)
                if (entitiesDirty) {
                    entitiesDirty = false
                    publish()
                }
            }
        }
    }

    /**
     * `subscribe_entities` rather than `subscribe_events: state_changed`: the
     * latter sends the full old AND new state of every entity on every change,
     * the former only the fields that changed. The first event under it is a
     * full snapshot, so no separate get_states is needed. Unfiltered, because
     * some cards look entities up by computed id (floorplan radar sensors) or
     * scan all of them (screensaver), so a config-derived list would miss some.
     */
    private fun subscribeEntities() {
        val id = idCounter.getAndIncrement()
        entitiesSubId = id
        awaitingSeed = true
        send(buildJsonObject {
            put("id", id)
            put("type", "subscribe_entities")
        })
    }

    private fun startHeartbeat() {
        val mine = epoch
        scope.launch {
            while (_connection.value == ConnectionState.CONNECTED && epoch == mine) {
                kotlinx.coroutines.delay(PING_INTERVAL_MS)
                // Re-check after the sleep: a ping landing on a fresh,
                // not-yet-authenticated socket makes HA drop the connection.
                if (_connection.value != ConnectionState.CONNECTED || epoch != mine) break
                val ping = buildJsonObject {
                    put("id", idCounter.getAndIncrement())
                    put("type", "ping")
                }
                send(ping)
            }
        }
    }

    /**
     * Push the store to [entities] and to the per-entity states of changed ids.
     * Synchronized: the publisher loop and the seed both call it, and two
     * overlapping snapshots writing the same state would conflict.
     */
    @Synchronized
    private fun publish() {
        _entities.value = HashMap(entityStore)
        val ids = changedIds.toList()
        changedIds.removeAll(ids.toSet())
        // One snapshot, so a batch of changes lands in a single frame.
        Snapshot.withMutableSnapshot {
            for (id in ids) entityStates[id]?.let { st ->
                val now = entityStore[id]
                if (st.value != now) st.value = now
            }
        }
    }

    private fun onEvent(obj: JsonObject) {
        val id = obj["id"]?.jsonPrimitive?.intOrNull
        val event = obj["event"] as? JsonObject ?: return
        if (id != null && id == entitiesSubId) {
            onEntitiesEvent(event)
            return
        }
        // Streaming commands (assist pipeline) claim their own ids.
        id?.let { eventHandlers[it] }?.invoke(event)
    }

    /**
     * A subscribe_entities event, in HA's compressed form:
     *   "a": { id: {s, a, c, lc, lu?} }            added (full state)
     *   "c": { id: {"+": {...}, "-": {"a": [..]}} } changed (a diff)
     *   "r": [ id, ... ]                           removed
     * lc/lu are epoch seconds; lu is only sent when it differs from lc.
     */
    @androidx.annotation.VisibleForTesting
    internal fun onEntitiesEvent(event: JsonObject) {
        val added = event["a"] as? JsonObject
        val seeded = awaitingSeed && added != null
        if (seeded) {
            awaitingSeed = false
            // The seed is the complete set: drop anything deleted from HA
            // while we were disconnected.
            for (gone in entityStore.keys - added!!.keys) {
                entityStore.remove(gone)
                changedIds += gone
            }
        }
        added?.forEach { (entityId, el) ->
            val c = el as? JsonObject ?: return@forEach
            val lc = isoTime(c["lc"])
            entityStore[entityId] = EntityState(
                entityId = entityId,
                state = c["s"]?.jsonPrimitive?.content ?: "unknown",
                attributes = c["a"] as? JsonObject ?: JsonObject(emptyMap()),
                lastChanged = lc,
                lastUpdated = isoTime(c["lu"]) ?: lc,
            )
            changedIds += entityId
        }
        (event["c"] as? JsonObject)?.forEach { (entityId, el) ->
            val diff = el as? JsonObject ?: return@forEach
            val old = entityStore[entityId] ?: return@forEach
            val plus = diff["+"] as? JsonObject
            val minusAttrs = ((diff["-"] as? JsonObject)?.get("a") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
            var attrs: Map<String, JsonElement> = old.attributes
            if (minusAttrs.isNotEmpty()) attrs = attrs - minusAttrs
            (plus?.get("a") as? JsonObject)?.let { attrs = attrs + it }
            val lc = isoTime(plus?.get("lc"))
            entityStore[entityId] = old.copy(
                state = plus?.get("s")?.jsonPrimitive?.content ?: old.state,
                attributes = if (attrs === old.attributes) old.attributes else JsonObject(attrs),
                lastChanged = lc ?: old.lastChanged,
                // A new last_changed implies the same last_updated.
                lastUpdated = isoTime(plus?.get("lu")) ?: lc ?: old.lastUpdated,
            )
            changedIds += entityId
        }
        (event["r"] as? JsonArray)?.forEach { el ->
            val entityId = el.jsonPrimitive.contentOrNull ?: return@forEach
            entityStore.remove(entityId)
            changedIds += entityId
        }
        // The seed is important — publish at once so the first frame has data.
        if (seeded) publish() else entitiesDirty = true
    }

    /** HA's epoch-seconds timestamp as ISO-8601, the format cards parse. */
    private fun isoTime(el: JsonElement?): String? =
        (el as? JsonPrimitive)?.doubleOrNull?.let { Instant.ofEpochMilli((it * 1000).toLong()).toString() }

    private fun send(msg: JsonObject) {
        // Never before auth_ok: HA drops a connection whose first message
        // isn't the auth message (see awaitConnected).
        if (_connection.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Not connected; dropped ${msg["type"]}")
            return
        }
        socket?.send(msg.toString())
    }

    /** Retry with backoff: 3 s, 6 s, 12 s… up to a minute while HA is unreachable. */
    private fun scheduleReconnect() {
        val wait = (RECONNECT_MIN_MS shl reconnectAttempts.coerceAtMost(5)).coerceAtMost(RECONNECT_MAX_MS)
        reconnectAttempts++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(wait)
            if (_connection.value == ConnectionState.ERROR) connect()
        }
    }

    private fun toWebSocketUrl(base: String): String {
        val trimmed = base.trimEnd('/')
        val ws = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> "ws://$trimmed"
        }
        return "$ws/api/websocket"
    }
}
