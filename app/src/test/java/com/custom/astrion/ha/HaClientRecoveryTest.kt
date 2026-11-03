package com.custom.astrion.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What keeps a remote usable after an HA outage without a reboot. */
class HaClientRecoveryTest {
    private fun event(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun cached(id: String, state: String) = EntityState(id, state, JsonObject(emptyMap()))

    private val seed = """
        {"a": {"light.kitchen": {"s": "on", "a": {}, "c": "01A", "lc": 1727600000.0}}}
    """

    /** 2026-11-01: a cache saved while HA was starting overwrote the live seed. */
    @Test fun cacheLoadedAfterTheSeedIsIgnored() {
        val c = HaClient("http://ha.local:8123", "token")
        c.subscribeEntities()
        c.onEntitiesEvent(event(seed))
        c.applyCachedSnapshot(mapOf(
            "light.kitchen" to cached("light.kitchen", "unavailable"),
            "light.hall" to cached("light.hall", "unavailable"),
        ))
        assertEquals("on", c.entityState("light.kitchen").value?.state)
        assertEquals(null, c.entityState("light.hall").value)
    }

    @Test fun cacheFillsInBeforeTheSeedWithoutOverwriting() {
        val c = HaClient("http://ha.local:8123", "token")
        c.applyCachedSnapshot(mapOf("light.hall" to cached("light.hall", "off")))
        assertEquals("off", c.entityState("light.hall").value?.state)
        c.subscribeEntities()
        c.onEntitiesEvent(event(seed))
        assertEquals("on", c.entityState("light.kitchen").value?.state)
    }

    @Test fun handshakeThatHangsIsStuck() {
        for (s in listOf(ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING)) {
            assertFalse(HaClient.isStuck(s, false, HaClient.STUCK_HANDSHAKE_MS - 1))
            assertTrue(HaClient.isStuck(s, false, HaClient.STUCK_HANDSHAKE_MS))
        }
    }

    @Test fun connectedIsOnlyStuckWhileTheSeedIsMissing() {
        assertFalse(HaClient.isStuck(ConnectionState.CONNECTED, false, Long.MAX_VALUE))
        assertFalse(HaClient.isStuck(ConnectionState.CONNECTED, true, HaClient.STUCK_SEED_MS - 1))
        assertTrue(HaClient.isStuck(ConnectionState.CONNECTED, true, HaClient.STUCK_SEED_MS))
    }

    @Test fun errorIsStuckOnlyPastTheLongestBackoff() {
        assertFalse(HaClient.isStuck(ConnectionState.ERROR, false, 60_000))
        assertTrue(HaClient.isStuck(ConnectionState.ERROR, false, HaClient.STUCK_ERROR_MS))
    }

    @Test fun deliberateStatesAreNeverStuck() {
        assertFalse(HaClient.isStuck(ConnectionState.DISCONNECTED, false, Long.MAX_VALUE))
        assertFalse(HaClient.isStuck(ConnectionState.AUTH_FAILED, false, Long.MAX_VALUE))
    }
}
