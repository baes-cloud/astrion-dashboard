package com.custom.astrion.ha

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

interface EntityCacheRepository {
    suspend fun loadInitialSnapshot(): Map<String, EntityState>
    suspend fun saveSnapshot(entities: Map<String, EntityState>)
    suspend fun enqueueOfflineCall(call: ServiceCall, confirm: Boolean = false)
    suspend fun drainOfflineCalls(ttlMs: Long = 30_000L): List<ServiceCall>
    suspend fun getQueueSize(): Int = 0
    suspend fun clearCache() {}
}

/**
 * Disk-backed implementation of EntityCacheRepository.
 * Stores snapshot in `filesDir/entity_cache.json` and offline action queue in
 * `filesDir/offline_action_queue.json`.
 * Uses atomic writes (.tmp -> rename) to protect against sudden power loss on MT6580.
 * Recovers cleanly from corrupted JSON without crashing.
 */
class DiskEntityCacheRepository(
    private val storageDir: File,
    private val maxQueueSize: Int = 50,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : EntityCacheRepository {

    constructor(context: Context) : this(context.filesDir)
    constructor(context: Context, clock: () -> Long) : this(context.filesDir, 50, clock)

    companion object {
        private const val TAG = "EntityCacheRepo"
        const val CACHE_FILE = "entity_cache.json"
        const val QUEUE_FILE = "offline_action_queue.json"
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    private val cacheFile = File(storageDir, CACHE_FILE)
    private val queueFile = File(storageDir, QUEUE_FILE)

    @Serializable
    internal data class CacheEnvelope(
        val version: Int = 1,
        val savedAtMs: Long = 0L,
        val entities: Map<String, CachedEntity>,
    )

    @Serializable
    internal data class CachedEntity(
        val entityId: String,
        val state: String,
        val attributes: JsonObject = JsonObject(emptyMap()),
        val lastChangedMs: Long? = null,
        val lastUpdatedMs: Long? = null,
    ) {
        fun toEntityState() = EntityState(entityId, state, attributes, lastChangedMs, lastUpdatedMs)
        companion object {
            fun from(e: EntityState) = CachedEntity(e.entityId, e.state, e.attributes, e.lastChangedMs, e.lastUpdatedMs)
        }
    }

    @Serializable
    internal data class QueuedAction(
        val domain: String,
        val service: String,
        val entityId: String? = null,
        val data: JsonObject = JsonObject(emptyMap()),
        val timestamp: Long = 0L,
        val confirm: Boolean = false,
    ) {
        fun toServiceCall() = ServiceCall(domain, service, entityId, data)
        companion object {
            fun from(call: ServiceCall, confirm: Boolean, timestamp: Long = System.currentTimeMillis()) = QueuedAction(
                domain = call.domain,
                service = call.service,
                entityId = call.entityId,
                data = JsonObject(call.data),
                timestamp = timestamp,
                confirm = confirm,
            )
        }
    }

    override suspend fun loadInitialSnapshot(): Map<String, EntityState> = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext emptyMap()
        try {
            val content = cacheFile.readText()
            if (content.isBlank()) return@withContext emptyMap()
            val envelope = json.decodeFromString<CacheEnvelope>(content)
            envelope.entities.mapValues { it.value.toEntityState() }
        } catch (e: Exception) {
            Log.w(TAG, "Corrupted cache file; resetting snapshot", e)
            runCatching { cacheFile.delete() }
            emptyMap()
        }
    }

    override suspend fun saveSnapshot(entities: Map<String, EntityState>): Unit = withContext(Dispatchers.IO) {
        try {
            if (!storageDir.exists()) storageDir.mkdirs()
            val envelope = CacheEnvelope(
                savedAtMs = clock(),
                entities = entities.mapValues { CachedEntity.from(it.value) }
            )
            val jsonText = json.encodeToString(CacheEnvelope.serializer(), envelope)
            atomicWrite(cacheFile, jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist snapshot to disk", e)
        }
        Unit
    }

    override suspend fun enqueueOfflineCall(call: ServiceCall, confirm: Boolean): Unit = withContext(Dispatchers.IO) {
        try {
            if (!storageDir.exists()) storageDir.mkdirs()
            val existing = readQueue().toMutableList()
            existing.add(QueuedAction.from(call, confirm, clock()))
            while (existing.size > maxQueueSize) {
                existing.removeAt(0) // Drop oldest when capacity exceeded
            }
            val jsonText = json.encodeToString(ListSerializer(QueuedAction.serializer()), existing)
            atomicWrite(queueFile, jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue offline action", e)
        }
        Unit
    }

    override suspend fun drainOfflineCalls(ttlMs: Long): List<ServiceCall> = withContext(Dispatchers.IO) {
        if (!queueFile.exists()) return@withContext emptyList()
        val actions = readQueue()
        runCatching { queueFile.delete() } // Atomically drain

        val now = clock()
        actions.filter { (now - it.timestamp) <= ttlMs }.map { it.toServiceCall() }
    }

    override suspend fun getQueueSize(): Int = withContext(Dispatchers.IO) {
        readQueue().size
    }

    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        runCatching { cacheFile.delete() }
        runCatching { queueFile.delete() }
        Unit
    }

    private fun readQueue(): List<QueuedAction> {
        if (!queueFile.exists()) return emptyList()
        return try {
            val content = queueFile.readText()
            if (content.isBlank()) emptyList()
            else json.decodeFromString(ListSerializer(QueuedAction.serializer()), content)
        } catch (e: Exception) {
            Log.w(TAG, "Corrupted queue file; clearing", e)
            runCatching { queueFile.delete() }
            emptyList()
        }
    }

    private fun atomicWrite(target: File, content: String) {
        val parent = target.parentFile ?: storageDir
        if (!parent.exists()) parent.mkdirs()
        val temp = File(parent, "${target.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }
}
