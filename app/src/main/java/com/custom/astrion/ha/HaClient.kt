package com.custom.astrion.ha

import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import com.custom.astrion.config.JsonPlain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
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
 *   5. Heartbeat via  WebSocket ping frames (OkHttp's pingInterval)
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
        private const val PUBLISH_INTERVAL_MS = 120L
        /** Stands in for an empty [setEntityFilter]; see subscribeEntities. */
        private const val NO_ENTITY = "sensor.astrion_no_entity"
        /** How long a request waits for the socket to (re)authenticate. */
        private const val CONNECT_WAIT_MS = 15_000L
        private const val RECONNECT_MIN_MS = 3_000L
        private const val RECONNECT_MAX_MS = 60_000L
        /** A call made while offline is sent on reconnect if it is younger than this. */
        private const val QUEUE_MAX_AGE_MS = 30_000L
        private const val QUEUE_MAX = 20
        private val PLAYHEAD_ATTRS = setOf("media_position", "media_position_updated_at")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicInteger(1)

    // The only heartbeat. There used to be a second, app-level HA `ping`
    // every 30 s on top of this; each one wakes the Wi-Fi radio, and only
    // this one actually notices a dead socket (no pong fails the connection).
    private val http = Http.base.newBuilder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Separate, sanely-timed client for one-shot HTTP GETs (album art). Both
    // derive from Http.base, so they share its connection pool and threads.
    private val imageHttp = Http.base.newBuilder()
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

    /** What happened to a service call; see [outcomes]. */
    data class CallOutcome(
        val call: ServiceCall,
        val kind: Kind,
        val error: String? = null,
    ) {
        enum class Kind { DONE, FAILED, QUEUED, DROPPED }
    }

    private val _outcomes = MutableSharedFlow<CallOutcome>(extraBufferCapacity = 16)
    /**
     * Failures always; successes only for calls made with `confirm = true`
     * (the hardware keys, which otherwise give no sign anything happened).
     * Calls made while offline are queued and reported as QUEUED, then sent
     * on reconnect, or DROPPED if HA was away longer than [QUEUE_MAX_AGE_MS].
     */
    val outcomes: SharedFlow<CallOutcome> = _outcomes.asSharedFlow()

    /** `call_service` replies awaited for [outcomes], keyed by request id. */
    private val resultHandlers = ConcurrentHashMap<Int, (JsonObject) -> Unit>()

    private class Queued(val at: Long, val call: ServiceCall, val confirm: Boolean)
    /** Calls made while not connected; flushed in [onAuthOk]. */
    private val offlineQueue = ArrayDeque<Queued>()

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    /**
     * Bumped once per publish. Reading it (via [live]'s iteration) is what
     * makes a whole-map reader recompose on any change; per-entity readers
     * never touch it.
     */
    private val version = mutableLongStateOf(0L)

    /** The copy [snapshot] last made, and the [version] it was made at. */
    @Volatile private var snap: EntityMap = emptyMap()
    @Volatile private var snapVersion = -1L

    /**
     * Every entity's state as a plain map, copied only when something has
     * changed since the last call. This used to be copied on every publish
     * (up to ~8 a second with the radar sensors live) whether or not
     * anything wanted the whole map; now only the screensaver and cards that
     * iterate every entity pay for it, and only when they ask.
     */
    fun snapshot(): EntityMap {
        val v = Snapshot.withoutReadObservation { version.longValue }
        if (v != snapVersion) {
            snap = HashMap(entityStore)
            snapVersion = v
        }
        return snap
    }

    /**
     * Live entities as a map. `live[id]` reads [entityState], so inside
     * Compose (or a `snapshotFlow`) it subscribes to that one entity only;
     * outside it is a plain lookup. Iterating it takes a [snapshot] and
     * subscribes to every change.
     */
    val live: EntityMap = object : AbstractMap<String, EntityState>() {
        override fun get(key: String): EntityState? = entityState(key).value
        override fun containsKey(key: String): Boolean = get(key) != null
        override val entries: Set<Map.Entry<String, EntityState>>
            get() {
                version.longValue // subscribe to every publish
                return snapshot().entries
            }
    }

    // Working store updated on every event; published to the UI at most once
    // per PUBLISH_INTERVAL_MS so a chatty sensor (e.g. mmWave radar at several
    // Hz) can't force the whole UI to repaint faster than the SoC can handle.
    // Scheduled by the event itself rather than a loop polling a dirty flag,
    // so nothing runs at all while HA is quiet.
    private val entityStore = ConcurrentHashMap<String, EntityState>()
    private val publishScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * One snapshot state per entity, for [entityState]. A composable that
     * reads one of these recomposes only when THAT entity changes, whereas
     * iterating [live] recomposes on any change anywhere in HA.
     */
    private val entityStates = ConcurrentHashMap<String, MutableState<EntityState?>>()
    /** Entities changed since the last publish, for the per-entity states. */
    private val changedIds = ConcurrentHashMap.newKeySet<String>()

    /** Request id of the live subscribe_entities, whose events are diffs. */
    @Volatile private var entitiesSubId = -1
    /** True until the current subscription's first (full-snapshot) event. */
    @Volatile private var awaitingSeed = false

    /**
     * Entities the live subscription is limited to; null = every entity.
     * See [setEntityFilter].
     */
    @Volatile private var entityFilter: Set<String>? = null
    /** The filter the live subscription was actually made with. */
    @Volatile private var subscribedFilter: Set<String>? = null

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
     * Disconnect for good: also stops this client's coroutines (reconnect,
     * publish, posts), which [disconnect] leaves alive for a later [connect].
     */
    fun close() {
        disconnect()
        scope.cancel()
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
     * Limit the entity subscription to [ids] (null: everything).
     *
     * With the screen off nothing is drawn, but an unfiltered subscription
     * still carries every change in HA — the floorplan's radar sensors alone
     * several times a second — and each packet wakes the Wi-Fi radio and the
     * CPU. MainActivity narrows it to what the alarm and alerts watch while
     * the screen is off, and widens it again on wake; the full snapshot that
     * re-subscribing brings refreshes everything that was missed meanwhile.
     */
    fun setEntityFilter(ids: Set<String>?) {
        entityFilter = ids
        if (_connection.value == ConnectionState.CONNECTED && ids != subscribedFilter) {
            val old = entitiesSubId
            if (old > 0) send(buildJsonObject {
                put("id", idCounter.getAndIncrement())
                put("type", "unsubscribe_events")
                put("subscription", old)
            })
            subscribeEntities()
        }
    }

    /**
     * Live state of one entity, as Compose state. Reading its value inside a
     * composable subscribes that scope to this entity alone.
     */
    fun entityState(entityId: String): State<EntityState?> =
        entityStates.getOrPut(entityId) { mutableStateOf(entityStore[entityId]) }

    /**
     * Fire a HA service call, e.g. light.toggle on light.kitchen. Offline, it
     * is queued rather than dropped (see [outcomes]); [confirm] also reports
     * success, not just failure.
     */
    fun callService(call: ServiceCall, confirm: Boolean = false) {
        if (_connection.value != ConnectionState.CONNECTED) {
            synchronized(offlineQueue) {
                offlineQueue.addLast(Queued(System.currentTimeMillis(), call, confirm))
                while (offlineQueue.size > QUEUE_MAX) offlineQueue.removeFirst()
            }
            _outcomes.tryEmit(CallOutcome(call, CallOutcome.Kind.QUEUED))
            return
        }
        val target = buildJsonObject {
            call.entityId?.let { put("entity_id", it) }
        }
        val id = idCounter.getAndIncrement()
        resultHandlers[id] = { reply ->
            val ok = reply["success"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!ok) {
                val err = (reply["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                _outcomes.tryEmit(CallOutcome(call, CallOutcome.Kind.FAILED, err))
            } else if (confirm) {
                _outcomes.tryEmit(CallOutcome(call, CallOutcome.Kind.DONE))
            }
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
        send(msg)
    }

    /** Convenience helper mirroring the common toggle pattern. */
    fun toggle(entityId: String) {
        val domain = entityId.substringBefore('.')
        callService(ServiceCall(domain = domain, service = "toggle", entityId = entityId))
    }

    /**
     * Fetch an image (e.g. a media_player `entity_picture`) as raw bytes, to
     * decode downsampled (ui/Bitmaps.kt) or cache (ArtCache). `path` may be
     * absolute or an HA-relative path like /api/media_player_proxy/…; the
     * bearer token is attached so proxied/authenticated art loads too.
     */
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
     * Set an entity's state through HA's REST API (`POST /api/states/<id>`),
     * e.g. the remote's own battery. The websocket API has no equivalent.
     * Fire and forget; a failure is only logged.
     */
    fun postState(entityId: String, state: String, attributes: Map<String, Any?>) {
        val body = buildJsonObject {
            put("state", state)
            put("attributes", JsonPlain.toJson(attributes))
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/states/" + entityId)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        scope.launch {
            runCatching { imageHttp.newCall(req).execute().close() }
                .onFailure { Log.w(TAG, "postState failed for $entityId", it) }
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

    /** A streaming subscription that should only run while the screen is on. */
    private class ForegroundSub(
        val build: JsonObjectBuilder.() -> Unit,
        val onEvent: (JsonObject) -> Unit,
    ) {
        @Volatile var id: Int? = null
    }

    private val foregroundSubs = mutableSetOf<ForegroundSub>()
    @Volatile private var foreground = true

    /**
     * Like [startSubscription], but only live while the screen is on (see
     * [setForeground]) and re-made on every reconnect. Screen-off has to be
     * handled here rather than in Compose: with the display off no frames
     * run, so an effect keyed on screen state never gets to cancel, and the
     * RMM stream (~1.4 KB/s on the wire) kept arriving all night.
     * Returns a function that cancels it for good.
     */
    fun startForegroundSubscription(
        build: JsonObjectBuilder.() -> Unit,
        onEvent: (JsonObject) -> Unit,
    ): () -> Unit {
        val sub = ForegroundSub(build, onEvent)
        synchronized(foregroundSubs) {
            foregroundSubs += sub
            if (foreground) sub.id = startSubscription(build, onEvent)
        }
        return {
            synchronized(foregroundSubs) {
                foregroundSubs -= sub
                sub.id?.let { unsubscribe(it) }
                sub.id = null
            }
        }
    }

    /** Screen on / off: start or stop every [startForegroundSubscription]. */
    fun setForeground(on: Boolean) {
        synchronized(foregroundSubs) {
            if (on == foreground) return
            foreground = on
            foregroundSubs.forEach { s ->
                if (on) {
                    if (s.id == null) s.id = startSubscription(s.build, s.onEvent)
                } else {
                    s.id?.let { unsubscribe(it) }
                    s.id = null
                }
            }
        }
    }

    /** A new socket: the old subscriptions died with the old one. */
    private fun resubscribeForeground() {
        synchronized(foregroundSubs) {
            foregroundSubs.forEach { s ->
                s.id?.let { eventHandlers.remove(it) }
                s.id = if (foreground) startSubscription(s.build, s.onEvent) else null
            }
        }
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

    /**
     * Send an audio frame the caller has already laid out as HA wants it:
     * `frame[0]` is the handler id, then [length]` - 1` bytes of PCM. Lets
     * the recorder reuse one buffer instead of allocating a frame per chunk.
     */
    fun sendAudioFrame(frame: ByteArray, length: Int, forEpoch: Int = epoch): Boolean {
        if (forEpoch != epoch) return false
        val sock = socket ?: return false
        return sock.send(frame.toByteString(0, length))
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
                        id?.let { resultHandlers.remove(it) }?.invoke(obj)
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
        // Replies to calls on the old socket will never come.
        resultHandlers.clear()
        subscribeEntities()
        resubscribeForeground()
        flushOfflineQueue()
    }

    /** Send what was tapped while offline, if it's still recent enough to mean it. */
    private fun flushOfflineQueue() {
        val queued = synchronized(offlineQueue) { offlineQueue.toList().also { offlineQueue.clear() } }
        val now = System.currentTimeMillis()
        queued.forEach { q ->
            if (now - q.at <= QUEUE_MAX_AGE_MS) callService(q.call, q.confirm)
            else _outcomes.tryEmit(CallOutcome(q.call, CallOutcome.Kind.DROPPED))
        }
    }

    /** Coalesce entity updates: publish the store at most every PUBLISH_INTERVAL_MS. */
    private fun schedulePublish() {
        if (publishScheduled.getAndSet(true)) return
        scope.launch {
            kotlinx.coroutines.delay(PUBLISH_INTERVAL_MS)
            publishScheduled.set(false)
            publish()
        }
    }

    /**
     * `subscribe_entities` rather than `subscribe_events: state_changed`: the
     * latter sends the full old AND new state of every entity on every change,
     * the former only the fields that changed. The first event under it is a
     * full snapshot, so no separate get_states is needed. Unfiltered, because
     * some cards look entities up by computed id (floorplan radar sensors) or
     * scan all of them (screensaver), so a config-derived list would miss some.
     * The exception is [setEntityFilter], used only while nothing is drawn.
     */
    @androidx.annotation.VisibleForTesting
    internal fun subscribeEntities() {
        val id = idCounter.getAndIncrement()
        val filter = entityFilter
        entitiesSubId = id
        subscribedFilter = filter
        awaitingSeed = true
        send(buildJsonObject {
            put("id", id)
            put("type", "subscribe_entities")
            // HA reads an empty entity_ids as "no filter" (everything), so an
            // empty filter subscribes to a placeholder that never exists.
            if (filter != null) {
                val ids = filter.ifEmpty { setOf(NO_ENTITY) }
                put("entity_ids", JsonArray(ids.map { JsonPrimitive(it) }))
            }
        })
    }

    /**
     * Push the store to the per-entity states of changed ids, and bump
     * [version] for whole-map readers.
     * Synchronized: the publisher loop and the seed both call it, and two
     * overlapping snapshots writing the same state would conflict.
     */
    @Synchronized
    private fun publish() {
        val ids = changedIds.toList()
        changedIds.removeAll(ids.toSet())
        // One snapshot, so a batch of changes lands in a single frame.
        Snapshot.withMutableSnapshot {
            for (id in ids) entityStates[id]?.let { st ->
                val now = entityStore[id]
                if (st.value != now) st.value = now
            }
            version.longValue += 1
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
        // Set by anything other than playhead noise; see isPlayheadNoise.
        var meaningful = false
        val added = event["a"] as? JsonObject
        val seeded = awaitingSeed && added != null
        if (seeded) {
            awaitingSeed = false
            // An unfiltered seed is the complete set: drop anything deleted
            // from HA while we were disconnected. A filtered one only covers
            // its own entities, so everything else is kept (stale) for the
            // full snapshot that comes back with the screen.
            if (subscribedFilter == null) for (gone in entityStore.keys - added!!.keys) {
                entityStore.remove(gone)
                changedIds += gone
            }
        }
        added?.forEach { (entityId, el) ->
            val c = el as? JsonObject ?: return@forEach
            val lc = epochMs(c["lc"])
            entityStore[entityId] = EntityState(
                entityId = entityId,
                state = c["s"]?.jsonPrimitive?.content ?: "unknown",
                attributes = c["a"] as? JsonObject ?: JsonObject(emptyMap()),
                lastChangedMs = lc,
                lastUpdatedMs = epochMs(c["lu"]) ?: lc,
            )
            changedIds += entityId
            meaningful = true
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
            val lc = epochMs(plus?.get("lc"))
            val updated = old.copy(
                state = plus?.get("s")?.jsonPrimitive?.content ?: old.state,
                attributes = if (attrs === old.attributes) old.attributes else JsonObject(attrs),
                lastChangedMs = lc ?: old.lastChangedMs,
                // A new last_changed implies the same last_updated.
                lastUpdatedMs = epochMs(plus?.get("lu")) ?: lc ?: old.lastUpdatedMs,
            )
            entityStore[entityId] = updated
            // Kept in the store, but nothing redraws for it; it goes out with
            // the next real change.
            if (isPlayheadNoise(plus, minusAttrs, updated.state)) return@forEach
            changedIds += entityId
            meaningful = true
        }
        (event["r"] as? JsonArray)?.forEach { el ->
            val entityId = el.jsonPrimitive.contentOrNull ?: return@forEach
            entityStore.remove(entityId)
            changedIds += entityId
        }
        (event["r"] as? JsonArray)?.let { if (it.isNotEmpty()) meaningful = true }
        // The seed is important — publish at once so the first frame has data.
        if (seeded) publish() else if (meaningful) schedulePublish()
    }

    /**
     * A diff that only moves the playhead of a player that isn't playing.
     * The Club TV's Cast session (Plex, paused) sent one of these about four
     * times a second, all day: nothing on screen changes for them, but each
     * one redrew whatever showed that player.
     */
    internal fun isPlayheadNoise(plus: JsonObject?, minusAttrs: Set<String>, state: String): Boolean {
        if (state == "playing" || minusAttrs.isNotEmpty() || plus == null) return false
        // "lu" (last_updated) and "c" (context) come with every diff.
        if (plus.keys.any { it != "a" && it != "lu" && it != "c" }) return false
        val attrs = plus["a"] as? JsonObject ?: return false
        return attrs.keys.all { it in PLAYHEAD_ATTRS }
    }

    /**
     * HA's epoch-seconds timestamp as epoch millis. Kept numeric: these
     * used to become ISO strings on every diff, only for readers to parse
     * them straight back.
     */
    private fun epochMs(el: JsonElement?): Long? =
        (el as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }

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
