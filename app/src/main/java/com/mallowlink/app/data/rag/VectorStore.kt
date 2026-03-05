package com.mallowlink.app.data.rag

import com.mallowlink.app.data.db.dao.ChunkDao
import com.mallowlink.app.data.db.dao.DocumentDao
import com.mallowlink.app.data.db.entity.DocumentChunkEntity
import com.mallowlink.app.data.model.EMBEDDING_DIM
import com.mallowlink.app.data.model.toFloatArray
import com.mallowlink.app.domain.model.DocumentChunk
import com.mallowlink.app.domain.model.FilterType
import com.mallowlink.app.domain.model.IndexedDocument
import com.mallowlink.app.domain.model.RetrievedChunk
import com.mallowlink.app.domain.model.SourceFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// VectorStore – In-memory Approximate Nearest Neighbour index
//
// Strategy:
//  • Load all chunk embeddings from Room into RAM on first query / after index
//    is rebuilt.  On a mid-range phone with ~3 GB RAM, 50 k chunks × 384 floats
//    ≈ 72 MB – well within budget.
//  • Use flat brute-force cosine similarity (fast enough for <100 k vectors on
//    modern ARMv8 with NEON SIMD; replace with HNSW via hnswlib-android for
//    larger corpora).
//  • Invalidated whenever new chunks are written; rebuilt lazily on next query.
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class VectorStore @Inject constructor(
    private val chunkDao: ChunkDao,
    private val documentDao: DocumentDao,
) {
    private val mutex = Mutex()
    private var indexEntries: List<IndexEntry> = emptyList()
    private var isDirty = true

    data class IndexEntry(
        val chunkId: String,
        val documentId: String,
        val embedding: FloatArray,
    )

    /** Call after any batch of new chunks is written to Room. */
    fun invalidate() { isDirty = true }

    /**
     * Returns the top-[topK] most similar chunks to [queryEmbedding].
     * Optionally filtered by [filter].
     */
    suspend fun search(
        queryEmbedding: FloatArray,
        topK: Int = 5,
        filter: SourceFilter = SourceFilter.ALL,
    ): List<RetrievedChunk> = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (isDirty) {
                rebuildIndex(filter)
            }
        }

        if (indexEntries.isEmpty()) return@withContext emptyList()

        // Cosine similarity (embeddings are L2-normalised, so dot product == cosine sim)
        val scored = indexEntries.map { entry ->
            val score = dotProduct(queryEmbedding, entry.embedding)
            entry to score
        }

        val topEntries = scored
            .sortedByDescending { it.second }
            .take(topK)

        // Hydrate from DB
        topEntries.mapNotNull { (entry, score) ->
            val chunkEntity = chunkDao.getById(entry.chunkId) ?: return@mapNotNull null
            val docEntity = documentDao.getById(entry.documentId) ?: return@mapNotNull null
            RetrievedChunk(
                chunk = chunkEntity.toDomain(),
                document = docEntity.toDomain(),
                score = score,
            )
        }
    }

    private suspend fun rebuildIndex(filter: SourceFilter) {
        Timber.d("Rebuilding VectorStore index…")
        val chunks: List<DocumentChunkEntity> = when (filter.type) {
            FilterType.ALL -> chunkDao.getAllFromEnabledSources()
            FilterType.MIME_TYPE -> chunkDao.getFiltered(filter.value, null)
            FilterType.FOLDER -> chunkDao.getFiltered(null, filter.value)
            FilterType.SOURCE_TYPE -> chunkDao.getFiltered(null, filter.value)
        }

        indexEntries = chunks
            .filter { it.embedding.size == EMBEDDING_DIM * 4 /* bytes */ }
            .map { entity ->
                IndexEntry(
                    chunkId = entity.id,
                    documentId = entity.documentId,
                    embedding = entity.embedding.toFloatArray(),
                )
            }

        isDirty = false
        Timber.d("VectorStore rebuilt: ${indexEntries.size} entries")
    }

    // ── Math helpers ─────────────────────────────────────────────────────────

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) sum += a[i] * b[i]
        return sum
    }
}

// ── Entity → domain mappers ──────────────────────────────────────────────────

fun DocumentChunkEntity.toDomain() = DocumentChunk(
    id = id,
    documentId = documentId,
    sourceId = sourceId,
    pageIndex = pageIndex,
    chunkIndex = chunkIndex,
    text = text,
    embedding = embedding.toFloatArray(),
    startChar = startChar,
    endChar = endChar,
)

fun com.mallowlink.app.data.db.entity.IndexedDocumentEntity.toDomain() = IndexedDocument(
    id = id,
    sourceId = sourceId,
    uriString = uriString,
    displayName = displayName,
    mimeType = mimeType,
    fileType = fileType,
    fileSizeBytes = fileSizeBytes,
    lastModified = lastModified,
    indexedAt = indexedAt,
    chunkCount = chunkCount,
    thumbnailPath = thumbnailPath,
)
