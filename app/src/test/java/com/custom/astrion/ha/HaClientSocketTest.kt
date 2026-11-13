package com.custom.astrion.ha

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** HaClient against a real websocket: the handshake, coalesced frames, a dropped socket. */
class HaClientSocketTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()
    /** Everything the client sent after `auth`, in order. */
    private val received = LinkedBlockingQueue<JsonObject>()
    @Volatile private var serverSocket: WebSocket? = null
    /** Called on the server for each command; lets a test answer or hang up. */
    @Volatile private var onCommand: (WebSocket, JsonObject) -> Unit = { _, _ -> }
    private lateinit var client: HaClient

    @Before fun setUp() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                webSocket.send("""{"type":"auth_required"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = Json.parseToJsonElement(text).jsonObject
                if (msg["type"]?.jsonPrimitive?.content == "auth") {
                    webSocket.send("""{"type":"auth_ok"}""")
                    return
                }
                received += msg
                onCommand(webSocket, msg)
            }
        }))
        server.start()
        client = HaClient(server.url("/").toString(), "token", clientOverride = http)
    }

    @After fun tearDown() {
        client.disconnect()
        runCatching { server.shutdown() }
        http.dispatcher.executorService.shutdown()
    }

    private fun next(type: String): JsonObject {
        while (true) {
            val m = received.poll(5, TimeUnit.SECONDS) ?: error("the client never sent $type")
            if (m["type"]?.jsonPrimitive?.content == type) return m
        }
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!ok()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test fun asksForCoalescedMessagesBeforeSubscribing() {
        client.connect()
        val first = received.poll(5, TimeUnit.SECONDS)
        assertEquals("supported_features", first?.get("type")?.jsonPrimitive?.content)
        assertEquals(1, first?.get("features")?.jsonObject?.get("coalesce_messages")?.jsonPrimitive?.int)
        assertEquals("subscribe_entities", received.poll(5, TimeUnit.SECONDS)?.get("type")?.jsonPrimitive?.content)
    }

    @Test fun aFrameCarryingSeveralMessagesIsAppliedInOrder() {
        client.connect()
        val sub = next("subscribe_entities")["id"]!!.jsonPrimitive.int
        serverSocket!!.send(
            """
            [
              {"id": $sub, "type": "event", "event": {"a": {"light.kitchen": {"s": "off", "a": {}, "lc": 1727600000.0}}}},
              {"id": $sub, "type": "event", "event": {"c": {"light.kitchen": {"+": {"s": "on"}}}}}
            ]
            """.trimIndent()
        )
        waitFor("the coalesced seed and diff") { client.current["light.kitchen"]?.state == "on" }
    }

    /** A reply that will never come used to hold its caller for the full 8 s. */
    @Test fun aDroppedSocketAnswersWaitingRequestsAtOnce() {
        client.connect()
        next("subscribe_entities")
        waitFor("connected") { client.connection.value == ConnectionState.CONNECTED }
        onCommand = { socket, msg ->
            if (msg["type"]?.jsonPrimitive?.content == "call_service") socket.close(1001, "restarting")
        }
        val started = System.nanoTime()
        val result = runBlocking {
            client.callServiceAwait(ServiceCall("script", "turn_on", "script.let_in"), timeoutMs = 8_000)
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(false, result)
        assertTrue("answered after $tookMs ms", tookMs < 4_000)
    }

    @Test fun losingTheNetworkDropsTheSocketWithoutWaitingForAPing() {
        client.connect()
        next("subscribe_entities")
        waitFor("connected") { client.connection.value == ConnectionState.CONNECTED }
        client.onNetworkLost()
        waitFor("the drop to be noticed") { client.connection.value != ConnectionState.CONNECTED }
    }

    @Test fun offlineCallsAreQueuedThenSentOrReportedDropped() {
        val outcomes = Collections.synchronizedList(mutableListOf<HaClient.CallOutcome>())
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { client.outcomes.collect { outcomes += it } }
        try {
            val on = ServiceCall("light", "turn_on", "light.kitchen")
            client.callService(on, confirm = true)
            client.callService(ServiceCall("light", "toggle", "light.kitchen"))
            waitFor("QUEUED and NOT_SENT") { outcomes.size == 2 }
            assertEquals(HaClient.CallOutcome.Kind.QUEUED, outcomes[0].kind)
            assertEquals(HaClient.CallOutcome.Kind.NOT_SENT, outcomes[1].kind)

            // HA was away too long: said so, not silently forgotten.
            client.flushOfflineQueue(now = System.currentTimeMillis() + 31_000)
            waitFor("DROPPED") { outcomes.any { it.kind == HaClient.CallOutcome.Kind.DROPPED } }

            // Back in time: sent on reconnect, and still confirmed.
            client.callService(on, confirm = true)
            client.connect()
            val sent = next("call_service")
            assertEquals("turn_on", sent["service"]?.jsonPrimitive?.content)
            assertNotNull(outcomes.firstOrNull { it.kind == HaClient.CallOutcome.Kind.SENT })
        } finally {
            scope.cancel()
        }
    }
}
