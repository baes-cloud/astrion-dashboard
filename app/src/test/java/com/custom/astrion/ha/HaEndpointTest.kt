package com.custom.astrion.ha

import org.junit.Assert.assertEquals
import org.junit.Test

class HaEndpointTest {
    private val built = HaEndpoint("http://built:8123", "built-token")

    @Test fun compiledInValuesAreTheDefault() {
        assertEquals(built, HaEndpoint.from(emptyMap(), built.url, built.token))
        assertEquals(built, HaEndpoint.from(mapOf("ha" to "nonsense"), built.url, built.token))
    }

    @Test fun deviceJsonOverridesEitherOne() {
        val options = mapOf("ha" to mapOf("url" to " http://192.0.2.10:8123 ", "token" to ""))
        assertEquals(HaEndpoint("http://192.0.2.10:8123", "built-token"), HaEndpoint.from(options, built.url, built.token))
        val both = mapOf("ha" to mapOf("url" to "https://ha.example", "token" to "abc"))
        assertEquals(HaEndpoint("https://ha.example", "abc"), HaEndpoint.from(both, built.url, built.token))
    }

    @Test fun theTokenOnlyGoesToHomeAssistantItself() {
        val c = HaClient("http://ha.local:8123", "secret")
        assertEquals(mapOf("Authorization" to "Bearer secret"), c.authHeadersFor(c.authedUrl("/api/tts_proxy/x.mp3")))
        assertEquals(emptyMap<String, String>(), c.authHeadersFor(c.authedUrl("https://cdn.example.com/x.mp3")))
        assertEquals(emptyMap<String, String>(), c.authHeadersFor("http://ha.local:9000/x.mp3"))
    }
}
