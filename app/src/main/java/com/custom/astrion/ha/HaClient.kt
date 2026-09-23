package com.custom.astrion.ha

import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import com.custom.astrion.ui.ImageCache
import com.custom.astrion.ui.decodeSampledBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What happened to a service call. */
enum class CallOutcome {
    /** HA answered `success: true`. */
    OK,
    /** HA answered `success: false` (bad service, bad data, entity refused). */
    FAILED,
    /** Never left the device: socket down or send() refused. */
    NOT_SENT,
    /**
     * No answer inside the timeout. Not treated as a failure: HA only answers
     * a directly-called script once the script has finished, which can take
     * longer than any sensible UI timeout.
     */
    NO_REPLY,
}

/**
 * Minimal, dependency-light Home Assistant WebSocket client.
 *
 * This speaks the *standard* HA websocket API — exactly the same handshake and
 * commands the stock HaRemote app uses (confirmed by decompiling it):
 *
 *   1. Server sends   { type: "auth_required" }
 *   2. We send        { type: "auth", access_token: "<long-lived token>" }
 *   3. Server sends   { type: "auth_ok" }  (or "auth_invalid")
 *   4. We call        get_states  to seed the entity cache
 *   5. We             subscribe_events (state_changed) for live updates
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
    private val baseUrl: String,   // e.g. "http://10.0.1.10:8123" or "https://ha.example.com"
    private val token: String,     // long-lived access token
) {
    companion object {
        private const val TAG = "HaClient"
        private const val PING_INTERVAL_MS = 30_000L
        private const val PUBLISH_INTERVAL_MS = 120L
        private const val CALL_TIMEOUT_MS = 12_000L
        private const val ALIVE_TIMEOUT_MS = 3_000L
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

    private var socket: WebSocket? = null

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
     * Per-entity observable cells. Reading one entity through [cell] (which
     * is what `CardContext.entity` does) subscribes that composable to THAT
     * entity only, so a radar target moving no longer recomposes the lock
     * card, the weather and the header. Created lazily on first read; the
     * publisher writes only the ids that actually changed.
     */
    private val cells = ConcurrentHashMap<String, MutableState<EntityState?>>()
    private val dirtyIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /**
     * Human-readable failures of service calls (refused, or not sent). The
     * UI shows these in the feedback strip so no action fails silently.
     */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val reconnectScheduled = AtomicBoolean(false)

    /**
     * Bumped on every connect / disconnect / forced reconnect. Each socket's
     * listener remembers the generation it was created for and ignores its
     * own callbacks once superseded — so a dead socket's late onFailure can't
     * knock a fresh connection back to ERROR. (A generation, not a socket
     * identity check, because onOpen can race the `socket =` assignment.)
     */
    @Volatile private var generation = 0

    // ---- public API ---------------------------------------------------------

    fun connect() {
        _connection.value = ConnectionState.CONNECTING
        val wsUrl = toWebSocketUrl(baseUrl)
        Log.i(TAG, "Connecting to $wsUrl")
        val req = Request.Builder().url(wsUrl).build()
        val gen = ++generation
        socket = http.newWebSocket(req, Listener(gen))
    }

    fun disconnect() {
        generation++
        socket?.close(1000, "client closing")
        socket = null
        _connection.value = ConnectionState.DISCONNECTED
    }

    /** Observable state of ONE entity. See [cells]. */
    fun cell(entityId: String): State<EntityState?> =
        cells.getOrPut(entityId) { mutableStateOf(entityStore[entityId]) }

    /** Current state of one entity, without subscribing anything. */
    fun peek(entityId: String): EntityState? = entityStore[entityId]

    /**
     * Fire a HA service call, e.g. light.toggle on light.kitchen. Sent
     * synchronously (so rapid presses keep their order); the reply is watched
     * in the background and a refusal is reported on [errors].
     */
    fun callService(call: ServiceCall) {
        val (id, waiter) = dispatch(call) ?: return
        scope.launch { report(call, awaitReply(id, waiter)) }
    }

    /** Fire a call and suspend until HA answers (or [CALL_TIMEOUT_MS]). */
    suspend fun callAwait(call: ServiceCall): CallOutcome {
        val (id, waiter) = dispatch(call) ?: return CallOutcome.NOT_SENT
        val outcome = awaitReply(id, waiter)
        report(call, outcome)
        return outcome
    }

    /** Send one call_service; null (already reported) if it couldn't be sent. */
    private fun dispatch(call: ServiceCall): Pair<Int, CompletableDeferred<JsonObject>>? {
        val sock = socket
        if (sock == null || _connection.value != ConnectionState.CONNECTED) {
            report(call, CallOutcome.NOT_SENT)
            return null
        }
        val id = idCounter.getAndIncrement()
        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = waiter
        val target = buildJsonObject {
            call.entityId?.let { put("entity_id", it) }
        }
        val msg = buildJsonObject {
            put("id", id)
            put("type", "call_service")
            put("domain", call.domain)
            put("service", call.service)
            if (call.data.isNotEmpty()) {
                put("service_data", JsonObject(call.data))
            }
            put("target", target)
        }
        if (!sock.send(msg.toString())) {
            pending.remove(id)
            report(call, CallOutcome.NOT_SENT)
            return null
        }
        return id to waiter
    }

    private suspend fun awaitReply(id: Int, waiter: CompletableDeferred<JsonObject>): CallOutcome {
        val reply = withTimeoutOrNull(CALL_TIMEOUT_MS) { waiter.await() }
        pending.remove(id)
        if (reply == null) return CallOutcome.NO_REPLY
        if (reply["success"]?.jsonPrimitive?.booleanOrNull == false) {
            failMessage = (reply["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            return CallOutcome.FAILED
        }
        return CallOutcome.OK
    }

    // Last refusal text, handed from [await] to [report]. Racy only in the
    // wording of simultaneous failures, never in whether one is reported.
    @Volatile private var failMessage: String? = null

    private fun report(call: ServiceCall, outcome: CallOutcome) {
        val what = describe(call)
        val text = when (outcome) {
            CallOutcome.OK, CallOutcome.NO_REPLY -> return
            CallOutcome.NOT_SENT -> "Not connected — $what wasn't sent"
            CallOutcome.FAILED -> "Home Assistant refused $what" +
                (failMessage?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        }
        Log.w(TAG, text)
        _errors.tryEmit(text)
    }

    /** "Kitchen (light.toggle)" — friendly enough for a one-line strip. */
    private fun describe(call: ServiceCall): String {
        val name = call.entityId?.let { entityStore[it]?.friendlyName }
        val svc = "${call.domain}.${call.service}"
        return if (name != null) "$name ($svc)" else svc
    }

    /**
     * Cheap liveness check for the wake path. After the screen has slept the
     * WiFi may have dropped while the socket still claims CONNECTED; a ping
     * with a short timeout finds out, and forces a reconnect if it's dead.
     */
    suspend fun ensureAlive() {
        if (_connection.value != ConnectionState.CONNECTED) return
        val sock = socket ?: return
        val id = idCounter.getAndIncrement()
        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = waiter
        val sent = sock.send(buildJsonObject { put("id", id); put("type", "ping") }.toString())
        val pong = if (sent) withTimeoutOrNull(ALIVE_TIMEOUT_MS) { waiter.await() } else null
        pending.remove(id)
        if (pong == null) {
            Log.w(TAG, "Socket looked alive but didn't answer a ping — reconnecting")
            forceReconnect()
        }
    }

    private fun forceReconnect() {
        val old = socket
        generation++ // the old socket's callbacks are ignored from here on
        socket = null
        old?.cancel()
        _connection.value = ConnectionState.ERROR
        scheduleReconnect()
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
    suspend fun fetchBitmap(path: String, targetPx: Int = 0): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val url = authedUrl(path)
            val key = ImageCache.remoteKey(url, targetPx)
            ImageCache.get(key)?.let { return@withContext it }
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            imageHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                // Downsampled to the size it's drawn at (album art was being
                // decoded at 640×640 ARGB = 1.6 MB for an 85dp tile) and
                // cached, so scrolling a shelf back doesn't refetch.
                decodeSampledBytes(bytes, targetPx)?.also { ImageCache.put(key, it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchBitmap failed for $path", e)
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
     * Send a raw binary frame. HA's Assist pipeline expects audio as
     * `[handler_id byte] + [raw PCM]`; a frame containing only the handler
     * byte signals end-of-audio.
     */
    fun sendAudioChunk(handlerId: Int, pcm: ByteArray, length: Int): Boolean {
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

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) return
            Log.i(TAG, "Socket open, waiting for auth_required")
            _connection.value = ConnectionState.AUTHENTICATING
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // A replaced socket's late messages must not touch the new state.
            if (gen != generation) return
            try {
                val obj = json.parseToJsonElement(text).jsonObject
                when (obj["type"]?.jsonPrimitive?.content) {
                    "auth_required" -> sendAuth(webSocket)
                    "auth_ok" -> onAuthOk()
                    "auth_invalid" -> _connection.value = ConnectionState.AUTH_FAILED
                    "result" -> {
                        // Route replies to an awaiting command if one matches this
                        // id; otherwise treat it as the get_states seed.
                        val id = obj["id"]?.jsonPrimitive?.intOrNull
                        val waiter = id?.let { pending.remove(it) }
                        if (waiter != null) waiter.complete(obj) else onResult(obj)
                    }
                    "event" -> onEvent(obj)
                    "pong" -> {
                        // Heartbeat, or an ensureAlive() probe waiting on its id.
                        val id = obj["id"]?.jsonPrimitive?.intOrNull
                        id?.let { pending.remove(it) }?.complete(obj)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onMessage parse error", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation) return
            Log.e(TAG, "Socket failure", t)
            _connection.value = ConnectionState.ERROR
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation) return
            Log.w(TAG, "Socket closed $code $reason")
            // A server-side close used to leave the state at CONNECTED, and
            // scheduleReconnect only reconnects from ERROR — so it never came
            // back. Mark it an error so the retry actually happens.
            if (_connection.value != ConnectionState.DISCONNECTED) {
                _connection.value = ConnectionState.ERROR
                scheduleReconnect()
            }
        }
    }

    private fun sendAuth(webSocket: WebSocket) {
        val msg = buildJsonObject {
            put("type", "auth")
            put("access_token", token)
        }
        // NOTE: auth message must NOT include an id (HA rejects it otherwise).
        webSocket.send(msg.toString())
    }

    private fun onAuthOk() {
        Log.i(TAG, "Authenticated")
        _connection.value = ConnectionState.CONNECTED
        startPublisher()
        requestStates()
        subscribeStateChanges()
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
                    val changed = ArrayList<String>()
                    val iter = dirtyIds.iterator()
                    while (iter.hasNext()) {
                        changed.add(iter.next())
                        iter.remove()
                    }
                    _entities.value = HashMap(entityStore)
                    publishCells(changed)
                }
            }
        }
    }

    private fun requestStates() {
        val msg = buildJsonObject {
            put("id", idCounter.getAndIncrement())
            put("type", "get_states")
        }
        send(msg)
    }

    private fun subscribeStateChanges() {
        val msg = buildJsonObject {
            put("id", idCounter.getAndIncrement())
            put("type", "subscribe_events")
            put("event_type", "state_changed")
        }
        send(msg)
    }

    private fun startHeartbeat() {
        scope.launch {
            while (_connection.value == ConnectionState.CONNECTED) {
                kotlinx.coroutines.delay(PING_INTERVAL_MS)
                val ping = buildJsonObject {
                    put("id", idCounter.getAndIncrement())
                    put("type", "ping")
                }
                send(ping)
            }
        }
    }

    /** Result of get_states arrives as an array in the `result` field. */
    private fun onResult(obj: JsonObject) {
        val result = obj["result"] as? JsonArray ?: return
        for (el in result) {
            val e = el.jsonObject
            val entityId = e["entity_id"]?.jsonPrimitive?.content ?: continue
            entityStore[entityId] = EntityState(
                entityId = entityId,
                state = e["state"]?.jsonPrimitive?.content ?: "unknown",
                attributes = e["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
                lastChanged = e["last_changed"]?.jsonPrimitive?.content,
                lastUpdated = e["last_updated"]?.jsonPrimitive?.content,
            )
        }
        // Seed is important — publish immediately so the first frame has data.
        _entities.value = HashMap(entityStore)
        publishCells(ArrayList(cells.keys))
    }

    /** Push changed entities into their cells, on the main thread. */
    private fun publishCells(ids: List<String>) {
        if (ids.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            for (id in ids) {
                val c = cells[id] ?: continue
                c.value = entityStore[id]
            }
        }
    }

    /** state_changed events carry event.data.new_state. */
    private fun onEvent(obj: JsonObject) {
        // Streaming commands (assist pipeline) claim their id first.
        val id = obj["id"]?.jsonPrimitive?.intOrNull
        val handler = id?.let { eventHandlers[it] }
        if (handler != null) {
            (obj["event"] as? JsonObject)?.let(handler)
            return
        }
        val data = obj["event"]?.jsonObject?.get("data")?.jsonObject ?: return
        val newState = data["new_state"] as? JsonObject ?: return
        val entityId = newState["entity_id"]?.jsonPrimitive?.content ?: return
        entityStore[entityId] = EntityState(
            entityId = entityId,
            state = newState["state"]?.jsonPrimitive?.content ?: "unknown",
            attributes = newState["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
            lastChanged = newState["last_changed"]?.jsonPrimitive?.content,
            lastUpdated = newState["last_updated"]?.jsonPrimitive?.content,
        )
        dirtyIds.add(entityId)
        entitiesDirty = true // published by the coalescing publisher loop
    }

    private fun send(msg: JsonObject) {
        socket?.send(msg.toString())
    }

    private fun scheduleReconnect() {
        // One pending retry at a time: a failure and a close can both land.
        if (!reconnectScheduled.compareAndSet(false, true)) return
        scope.launch {
            kotlinx.coroutines.delay(3_000)
            reconnectScheduled.set(false)
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
