package com.custom.astrion.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed class SyncResult {
    data class Success(val updatedCount: Int) : SyncResult()
    data class Failure(val error: String) : SyncResult()
    object Skipped : SyncResult()
}

interface SyncRepository {
    suspend fun syncRemotePresets(remoteId: String): SyncResult
    suspend fun pushStateSnapshot(remoteId: String, snapshot: Map<String, Any?>)
    fun observeRemotePresets(remoteId: String): Flow<Map<String, Any?>>
}

/**
 * Local / no-op fallback when cloud sync is disabled.
 * Zero external dependencies, preserving < 2.5 MB APK constraint.
 */
class LocalSyncRepository : SyncRepository {
    override suspend fun syncRemotePresets(remoteId: String): SyncResult = SyncResult.Skipped
    override suspend fun pushStateSnapshot(remoteId: String, snapshot: Map<String, Any?>) {}
    override fun observeRemotePresets(remoteId: String): Flow<Map<String, Any?>> = emptyFlow()
}
