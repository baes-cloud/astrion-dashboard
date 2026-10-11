package com.custom.astrion.ha

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Adversarial edge-case test suite for [DiskEntityCacheRepository].
 * Tests corrupted disk state, empty/whitespace files, queue boundaries,
 * serialization atomicity (.tmp collision), and extreme payloads.
 */
class EntityCacheRepositoryAdversarialTest {

    private lateinit var tempDir: File
    private lateinit var repo: DiskEntityCacheRepository

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("entity_cache_adv_test").toFile()
        repo = DiskEntityCacheRepository(storageDir = tempDir, maxQueueSize = 50)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun corruptedDiskState_garbageBinaryBytesReturnsEmptySnapshotAndDeletesFile() = runBlocking {
        val cacheFile = File(tempDir, DiskEntityCacheRepository.CACHE_FILE)
        cacheFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, -1, -128, 0x7F, '{'.code.toByte(), 'b'.code.toByte()))

        val loaded = repo.loadInitialSnapshot()
        assertTrue("Corrupted binary garbage must return empty snapshot", loaded.isEmpty())
        assertFalse("Corrupted file must be deleted upon recovery", cacheFile.exists())
    }

    @Test
    fun corruptedDiskState_truncatedJsonObjectReturnsEmptySnapshot() = runBlocking {
        val cacheFile = File(tempDir, DiskEntityCacheRepository.CACHE_FILE)
        cacheFile.writeText("""{"version": 1, "entities": {"light.1": {"entityId": "light.1"""")

        val loaded = repo.loadInitialSnapshot()
        assertTrue("Truncated JSON must recover cleanly to emptyMap", loaded.isEmpty())
        assertFalse("Corrupted truncated file must be deleted", cacheFile.exists())
    }

    @Test
    fun corruptedDiskState_invalidSchemaTypeReturnsEmptySnapshot() = runBlocking {
        val cacheFile = File(tempDir, DiskEntityCacheRepository.CACHE_FILE)
        cacheFile.writeText("""{"version": "non_integer_version", "entities": "not_an_object"}""")

        val loaded = repo.loadInitialSnapshot()
        assertTrue("Schema mismatch must return empty snapshot", loaded.isEmpty())
        assertFalse("Corrupted schema file must be deleted", cacheFile.exists())
    }

    @Test
    fun emptySnapshotRecovery_zeroByteFileReturnsEmptyWithoutCrash() = runBlocking {
        val cacheFile = File(tempDir, DiskEntityCacheRepository.CACHE_FILE)
        cacheFile.writeText("")

        val loaded = repo.loadInitialSnapshot()
        assertTrue("0-byte file must recover cleanly to emptyMap", loaded.isEmpty())
    }

    @Test
    fun emptySnapshotRecovery_whitespaceOnlyFileReturnsEmptyWithoutCrash() = runBlocking {
        val cacheFile = File(tempDir, DiskEntityCacheRepository.CACHE_FILE)
        cacheFile.writeText("   \t  \r\n \n   ")

        val loaded = repo.loadInitialSnapshot()
        assertTrue("Whitespace-only file must return emptyMap", loaded.isEmpty())
    }

    @Test
    fun emptySnapshotRecovery_emptyEntitiesMapPreserved() = runBlocking {
        repo.saveSnapshot(emptyMap())

        val loaded = repo.loadInitialSnapshot()
        assertTrue("Empty entities snapshot must be saved and loaded as emptyMap", loaded.isEmpty())
    }

    @Test
    fun corruptedQueueRecovery_garbageQueueFileCleansAndRecovers() = runBlocking {
        val queueFile = File(tempDir, DiskEntityCacheRepository.QUEUE_FILE)
        queueFile.writeBytes(byteArrayOf(0x1F, 0x2A, 0x3B, 0x4C, 0x5D))

        assertEquals("Garbage queue file must report size 0", 0, repo.getQueueSize())
        assertTrue("Garbage queue file must drain as emptyList", repo.drainOfflineCalls().isEmpty())

        // Ensure repo can enqueue new call after recovering from corrupted queue
        val call = ServiceCall("light", "turn_on", "light.restored")
        repo.enqueueOfflineCall(call)
        assertEquals("Queue must recover and store new call", 1, repo.getQueueSize())

        val drained = repo.drainOfflineCalls(ttlMs = 60_000L)
        assertEquals(1, drained.size)
        assertEquals("light.restored", drained[0].entityId)
    }

    @Test
    fun queueOverflowBounds_boundaryZeroDropsAllCalls() = runBlocking {
        val zeroCapacityRepo = DiskEntityCacheRepository(storageDir = tempDir, maxQueueSize = 0)

        for (i in 1..5) {
            zeroCapacityRepo.enqueueOfflineCall(ServiceCall("light", "toggle", "light.$i"))
        }

        assertEquals("Zero capacity queue must have size 0", 0, zeroCapacityRepo.getQueueSize())
        assertTrue("Zero capacity queue must drain empty", zeroCapacityRepo.drainOfflineCalls().isEmpty())
    }

    @Test
    fun queueOverflowBounds_boundaryOneRetainsOnlyLatestItem() = runBlocking {
        val singleCapacityRepo = DiskEntityCacheRepository(storageDir = tempDir, maxQueueSize = 1)

        for (i in 1..10) {
            singleCapacityRepo.enqueueOfflineCall(ServiceCall("light", "toggle", "light.$i"))
        }

        assertEquals("Single capacity queue must have size 1", 1, singleCapacityRepo.getQueueSize())
        val drained = singleCapacityRepo.drainOfflineCalls(ttlMs = 60_000L)
        assertEquals(1, drained.size)
        assertEquals("light.10", drained[0].entityId)
    }

    @Test
    fun queueOverflowBounds_exactCapacityAndOverflowOrdering() = runBlocking {
        val capRepo = DiskEntityCacheRepository(storageDir = tempDir, maxQueueSize = 5)

        for (i in 1..5) {
            capRepo.enqueueOfflineCall(ServiceCall("switch", "turn_on", "switch.$i"))
        }
        assertEquals(5, capRepo.getQueueSize())

        // Enqueue 6th item: should drop switch.1 and keep switch.2..switch.6
        capRepo.enqueueOfflineCall(ServiceCall("switch", "turn_on", "switch.6"))
        assertEquals(5, capRepo.getQueueSize())

        val drained = capRepo.drainOfflineCalls(ttlMs = 60_000L)
        assertEquals(5, drained.size)
        assertEquals("switch.2", drained[0].entityId)
        assertEquals("switch.3", drained[1].entityId)
        assertEquals("switch.4", drained[2].entityId)
        assertEquals("switch.5", drained[3].entityId)
        assertEquals("switch.6", drained[4].entityId)
    }

    @Test
    fun diskSerializationAtomicity_existingDirtyTmpFileOverwrittenCleanly() = runBlocking {
        val tmpFile = File(tempDir, "${DiskEntityCacheRepository.CACHE_FILE}.tmp")
        tmpFile.writeText("DIRTY_STALE_TMP_CONTENT_FROM_PREVIOUS_CRASH")

        val entities = mapOf(
            "climate.ac" to EntityState(
                entityId = "climate.ac",
                state = "cool",
                attributes = buildJsonObject { put("current_temp", JsonPrimitive(23.5)) }
            )
        )

        repo.saveSnapshot(entities)

        val loaded = repo.loadInitialSnapshot()
        assertEquals(1, loaded.size)
        assertEquals("cool", loaded["climate.ac"]?.state)
        assertEquals(23.5, loaded["climate.ac"]?.attrDouble("current_temp")!!, 0.0)
        assertFalse("Temporary file should be renamed/deleted after atomic write", tmpFile.exists())
    }

    @Test
    fun diskSerializationAtomicity_autoCreatesMissingStorageDirectories() = runBlocking {
        val nestedDir = File(tempDir, "sub/deep/nested/cache")
        val nestedRepo = DiskEntityCacheRepository(storageDir = nestedDir, maxQueueSize = 10)

        assertFalse("Storage dir must not exist prior to write", nestedDir.exists())

        nestedRepo.saveSnapshot(mapOf("sensor.1" to EntityState("sensor.1", "active", JsonObject(emptyMap()))))
        assertTrue("Storage dir must be automatically created", nestedDir.exists())

        val loaded = nestedRepo.loadInitialSnapshot()
        assertEquals(1, loaded.size)
        assertEquals("active", loaded["sensor.1"]?.state)
    }

    @Test
    fun extremePayload_unicodeSpecialCharactersAndNestedJsonPreserved() = runBlocking {
        val specialAttributes = buildJsonObject {
            put("emoji_title", JsonPrimitive("Astrion 🚀 💡 🔥"))
            put("quotes_and_escapes", JsonPrimitive("Line1\nLine2\t\"quoted\" \\escaped\\"))
            put("chinese_chars", JsonPrimitive("智能家居控制面板"))
            put("nested_data", buildJsonObject {
                put("sub_key", JsonPrimitive(42))
                put("bool_flag", JsonPrimitive(true))
            })
            put("json_array", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("two"), JsonNull)))
        }

        val entities = mapOf(
            "sensor.extreme" to EntityState(
                entityId = "sensor.extreme",
                state = "complex",
                attributes = specialAttributes,
                lastChangedMs = 1729999999999L,
                lastUpdatedMs = 1730000000000L,
            )
        )

        repo.saveSnapshot(entities)

        val loaded = repo.loadInitialSnapshot()
        val restored = loaded["sensor.extreme"]
        assertNotNull(restored)
        assertEquals("complex", restored?.state)
        assertEquals("Astrion 🚀 💡 🔥", restored?.attrString("emoji_title"))
        assertEquals("智能家居控制面板", restored?.attrString("chinese_chars"))
        assertEquals(1729999999999L, restored?.lastChangedMs)
        assertEquals(1730000000000L, restored?.lastUpdatedMs)
    }

    @Test
    fun drainAtomicity_doubleDrainIsIdempotentAndRemovesQueueFile() = runBlocking {
        repo.enqueueOfflineCall(ServiceCall("light", "turn_on", "light.first"))
        repo.enqueueOfflineCall(ServiceCall("light", "turn_off", "light.second"))

        val firstDrain = repo.drainOfflineCalls(ttlMs = 60_000L)
        assertEquals(2, firstDrain.size)

        val secondDrain = repo.drainOfflineCalls(ttlMs = 60_000L)
        assertTrue("Second drain must be empty", secondDrain.isEmpty())
        assertFalse("Queue file must not exist after drain", File(tempDir, DiskEntityCacheRepository.QUEUE_FILE).exists())
    }

    @Test
    fun serviceCallWithNullEntityIdAndDataPayloadEnqueuedAccurately() = runBlocking {
        val globalCall = ServiceCall(
            domain = "homeassistant",
            service = "restart",
            entityId = null,
            data = mapOf("safe_mode" to JsonPrimitive(true))
        )

        repo.enqueueOfflineCall(globalCall)

        val drained = repo.drainOfflineCalls(ttlMs = 60_000L)
        assertEquals(1, drained.size)
        assertEquals("homeassistant", drained[0].domain)
        assertEquals("restart", drained[0].service)
        assertNull(drained[0].entityId)
        assertEquals(JsonPrimitive(true), drained[0].data["safe_mode"])
    }

    @Test
    fun rapidSequentialSavesMaintainIntegrity() = runBlocking {
        for (i in 1..50) {
            val entities = mapOf(
                "sensor.counter" to EntityState(
                    entityId = "sensor.counter",
                    state = "$i",
                    attributes = buildJsonObject { put("iteration", JsonPrimitive(i)) }
                )
            )
            repo.saveSnapshot(entities)
        }

        val loaded = repo.loadInitialSnapshot()
        assertEquals(1, loaded.size)
        assertEquals("50", loaded["sensor.counter"]?.state)
        assertEquals(50.0, loaded["sensor.counter"]?.attrDouble("iteration")!!, 0.0)
    }
}
