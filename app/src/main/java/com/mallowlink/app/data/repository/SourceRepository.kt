package com.mallowlink.app.data.repository

import android.net.Uri
import androidx.work.WorkManager
import com.mallowlink.app.data.db.dao.ChunkDao
import com.mallowlink.app.data.db.dao.DocumentDao
import com.mallowlink.app.data.db.dao.SourceDao
import com.mallowlink.app.data.db.entity.IndexedSourceEntity
import com.mallowlink.app.domain.model.FileType
import com.mallowlink.app.domain.model.IndexedDocument
import com.mallowlink.app.domain.model.IndexedSource
import com.mallowlink.app.domain.model.SourceType
import com.mallowlink.app.workers.IndexingWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRepository @Inject constructor(
    private val sourceDao: SourceDao,
    private val documentDao: DocumentDao,
    private val chunkDao: ChunkDao,
    private val workManager: WorkManager,
) {

    fun observeSources(): Flow<List<IndexedSource>> =
        sourceDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeDocuments(sourceId: String): Flow<List<IndexedDocument>> =
        documentDao.observeBySource(sourceId).map { list ->
            list.map { doc ->
                IndexedDocument(
                    id = doc.id,
                    sourceId = doc.sourceId,
                    uriString = doc.uriString,
                    displayName = doc.displayName,
                    mimeType = doc.mimeType,
                    fileType = doc.fileType,
                    fileSizeBytes = doc.fileSizeBytes,
                    lastModified = doc.lastModified,
                    indexedAt = doc.indexedAt,
                    chunkCount = doc.chunkCount,
                    thumbnailPath = doc.thumbnailPath,
                )
            }
        }

    suspend fun addSource(uri: Uri, displayName: String, type: SourceType): IndexedSource {
        val id = UUID.randomUUID().toString()
        val entity = IndexedSourceEntity(
            id = id,
            displayName = displayName,
            uriString = uri.toString(),
            type = type,
            isEnabled = true,
            lastSyncedAt = 0L,
        )
        sourceDao.upsert(entity)

        // Trigger immediate indexing
        workManager.enqueue(
            IndexingWorker.buildRequest(id, uri.toString(), force = true)
        )
        return entity.toDomain()
    }

    suspend fun removeSource(sourceId: String) {
        val entity = sourceDao.getById(sourceId) ?: return
        // Cancel any in-flight indexing for this source
        workManager.cancelAllWorkByTag("indexing_$sourceId")
        sourceDao.delete(entity)
        // Cascade deletes documents + chunks via FK
    }

    suspend fun toggleSource(sourceId: String, enabled: Boolean) {
        val entity = sourceDao.getById(sourceId) ?: return
        sourceDao.update(entity.copy(isEnabled = enabled))
    }

    suspend fun forgetFile(documentId: String) {
        chunkDao.deleteByDocument(documentId)
        documentDao.deleteById(documentId)
    }

    suspend fun reindexSource(sourceId: String) {
        val entity = sourceDao.getById(sourceId) ?: return
        workManager.enqueue(
            IndexingWorker.buildRequest(sourceId, entity.uriString, force = true)
        )
    }

    private fun IndexedSourceEntity.toDomain() = IndexedSource(
        id = id,
        displayName = displayName,
        uriString = uriString,
        type = type,
        isEnabled = isEnabled,
        lastSyncedAt = lastSyncedAt,
        documentCount = documentCount,
    )
}
