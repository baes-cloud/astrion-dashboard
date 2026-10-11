package com.custom.astrion.ha

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EntityCacheRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repo: DiskEntityCacheRepository

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("entity_cache_test").toFile()
        repo = DiskEntityCacheRepository(storageDir = tempDir, maxQueueSize = 50)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun saveAndLoadSnapshotRoundTrip() = runBlocking {
        val entities = mapOf(
            "light.living_room" to EntityState(
                entityId = "light.living_room",
                state = "on",
                attributes = buildJsonObject {
                    put("brightness", JsonPrimitive(255))
                    put("friendly_name", JsonPrimitive("Living Room"))
                },
                lastChangedMs = 1720000000000L,
                lastUpdatedMs = 1720000001000L,
            ),
            "sensor.temperature" to EntityState(
                entityId = "sensor.temperature",
                state = "22.5",
                attributes = buildJsonObject {
                    put("unit_of_measurement", JsonPrimitive("°C"))
                },
                lastChangedMs = 1720000002000L,
                lastUpdatedMs = 1720000003000L,
            )
        )

        repo.saveSnapshot(entities)

        val loaded = repo.loadInitialSnapshot()
        assertEquals(2, loaded.size)
        assertEquals("on", loaded["light.living_room"]?.state)
        assertEquals("Living Room", loaded["light.living_room"]?.friendlyName)
        assertEquals(255.0, loaded["light.living_room"]?.attrDouble("brightness")!!, 0.0)
        assertEquals("22.5", loaded["sensor.temperature"]?.state)
        assertEquals("°C", loaded["sensor.temperature"]?.attrString("unit_of_measurement"))
    }

    @Test
    fun loadInitialSnapshotRecoversFromCorruptedJson() = runBlocking {
        val cacheFile = File(tempDir, DiskEntityCacheRepository.CACHE_FILE)
        cacheFile.writeText("{ corrupted invalid json ::::")

        val loaded = repo.loadInitialSnapshot()
        assertTrue("Corrupted file must result in emptyMap() without crashing", loaded.isEmpty())
        assertFalse("Corrupted cache file should be deleted or reset", cacheFile.exists())
    }

    @Test
    fun loadInitialSnapshotReturnsEmptyWhenNoFile() = runBlocking {
        val loaded = repo.loadInitialSnapshot()
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun enqueueOfflineCallAndDrain() = runBlocking {
        val call1 = ServiceCall.of("light", "turn_on", "light.kitchen", "brightness" to 128)
        val call2 = ServiceCall("switch", "turn_off", "switch.fan")

        repo.enqueueOfflineCall(call1, confirm = false)
        repo.enqueueOfflineCall(call2, confirm = true)

        assertEquals(2, repo.getQueueSize())

        val drained = repo.drainOfflineCalls(ttlMs = 60_000L)
        assertEquals(2, drained.size)
        assertEquals("light", drained[0].domain)
        assertEquals("turn_on", drained[0].service)
        assertEquals("light.kitchen", drained[0].entityId)

        assertEquals("switch", drained[1].domain)
        assertEquals("turn_off", drained[1].service)
        assertEquals("switch.fan", drained[1].entityId)

        assertEquals(0, repo.getQueueSize())
    }

    @Test
    fun queueCapacityPrunesOldestItems() = runBlocking {
        val customRepo = DiskEntityCacheRepository(storageDir = tempDir, maxQueueSize = 5)

        for (i in 1..10) {
            customRepo.enqueueOfflineCall(ServiceCall("light", "call_$i", "light.item_$i"))
        }

        assertEquals(5, customRepo.getQueueSize())
        val drained = customRepo.drainOfflineCalls(ttlMs = 60_000L)
        assertEquals(5, drained.size)
        assertEquals("light.item_6", drained[0].entityId)
        assertEquals("light.item_10", drained[4].entityId)
    }

    @Test
    fun drainOfflineCallsFiltersTtlExpired() = runBlocking {
        var simulatedTime = 1_000_000L
        val timedRepo = DiskEntityCacheRepository(
            storageDir = tempDir,
            maxQueueSize = 50,
            clock = { simulatedTime }
        )
        val call = ServiceCall("light", "turn_on", "light.bed")
        timedRepo.enqueueOfflineCall(call)

        // Advance simulated clock past TTL
        simulatedTime += 2_000L
        val drained = timedRepo.drainOfflineCalls(ttlMs = 1_000L)
        assertEquals("Expired calls should be filtered out, but drained was not empty", emptyList<ServiceCall>(), drained)
    }

    @Test
    fun drainOfflineCallsPreservesUnexpiredCalls() = runBlocking {
        var simulatedTime = 1_000_000L
        val timedRepo = DiskEntityCacheRepository(
            storageDir = tempDir,
            maxQueueSize = 50,
            clock = { simulatedTime }
        )
        val expiredCall = ServiceCall("light", "turn_on", "light.bed")
        timedRepo.enqueueOfflineCall(expiredCall)

        simulatedTime += 5_000L
        val freshCall = ServiceCall("switch", "turn_off", "switch.fan")
        timedRepo.enqueueOfflineCall(freshCall)

        val drained = timedRepo.drainOfflineCalls(ttlMs = 2_000L)
        assertEquals(1, drained.size)
        assertEquals("switch.fan", drained[0].entityId)
    }

    @Test
    fun clearCacheRemovesAllFiles() = runBlocking {
        repo.saveSnapshot(mapOf("sensor.test" to EntityState("sensor.test", "1", JsonObject(emptyMap()))))
        repo.enqueueOfflineCall(ServiceCall("light", "toggle"))

        assertTrue(File(tempDir, DiskEntityCacheRepository.CACHE_FILE).exists())
        assertTrue(File(tempDir, DiskEntityCacheRepository.QUEUE_FILE).exists())

        repo.clearCache()

        assertFalse(File(tempDir, DiskEntityCacheRepository.CACHE_FILE).exists())
        assertFalse(File(tempDir, DiskEntityCacheRepository.QUEUE_FILE).exists())
    }
}
