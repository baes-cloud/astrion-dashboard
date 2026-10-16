package com.custom.astrion.cards.impl

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexModelTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    // Trimmed from the real server's /library/onDeck and /library/sections/1/recentlyAdded.
    private val onDeck = obj("""
        {"MediaContainer": {"size": 2, "Metadata": [
          {"ratingKey": "17482", "type": "episode", "title": "Pilot", "grandparentTitle": "The Diplomat",
           "parentIndex": 4, "index": 6, "viewOffset": 120000,
           "thumb": "/library/metadata/17458/thumb/1792049769",
           "grandparentThumb": "/library/metadata/17372/thumb/1792052825"},
          {"ratingKey": "900", "type": "movie", "title": "Animals", "year": 2026,
           "thumb": "/library/metadata/900/thumb/1"}
        ]}}
    """)

    @Test fun episodesShowTheShowAndItsNumber() {
        val ep = parsePlexItems(onDeck)!![0]
        assertEquals("The Diplomat", ep.title)
        assertEquals("S4E6", ep.subtitle)
        assertEquals("/library/metadata/17372/thumb/1792052825", ep.thumb)
        assertTrue(ep.resume)
    }

    @Test fun moviesShowTheirYear() {
        val m = parsePlexItems(onDeck)!![1]
        assertEquals("Animals", m.title)
        assertEquals("2026", m.subtitle)
        assertFalse(m.resume)
    }

    @Test fun noMetadataIsNull() {
        assertNull(parsePlexItems(obj("""{"MediaContainer": {"size": 0}}""")))
        assertNull(parsePlexItems(obj("""{"error": "x"}""")))
    }

    @Test fun postersAreRequestedAtTheTileSize() {
        val u = plexThumbUrl("http://10.0.0.10:32400", "", "/library/metadata/1/thumb/2", 116, 165)
        assertEquals(
            "http://10.0.0.10:32400/photo/:/transcode?width=116&height=165&minSize=1&upscale=1&url=%2Flibrary%2Fmetadata%2F1%2Fthumb%2F2",
            u,
        )
        assertTrue(plexThumbUrl("http://h", "tok", "/t", 1, 1).endsWith("&X-Plex-Token=tok"))
    }

    @Test fun tokenOnlyWhenThereIsOne() {
        assertEquals("http://h/identity", plexUrl("http://h", "", "/identity"))
        assertEquals("http://h/identity?X-Plex-Token=t", plexUrl("http://h", "t", "/identity"))
        assertEquals("http://h/a?b=1&X-Plex-Token=t", plexUrl("http://h", "t", "/a?b=1"))
    }

    @Test fun emptyShelvesSayWhy() {
        assertEquals("Plex — set plex.host in dashboard.json", plexEmptyMessage("http://YOUR_PLEX_IP:32400", "unreachable"))
        assertEquals("Plex — can't load from 10.0.0.10:32400 (HTTP 401)", plexEmptyMessage("http://10.0.0.10:32400", "HTTP 401"))
        assertEquals("Plex — nothing to show", plexEmptyMessage("http://10.0.0.10:32400", null))
    }

    @Test fun shelvesRoundTripForTheDiskCache() {
        val shelves = listOf(PlexShelf("On Deck", parsePlexItems(onDeck)!!))
        val ser = ListSerializer(PlexShelf.serializer())
        assertEquals(shelves, Json.decodeFromString(ser, Json.encodeToString(ser, shelves)))
    }

    @Test fun deepLinkAddressesTheItemOnThisServer() {
        assertEquals(
            "am start -a android.intent.action.VIEW -d 'plex://server://abc/com.plexapp.plugins.library/library/metadata/17482'",
            plexDeepLink("abc", "17482"),
        )
    }
}
