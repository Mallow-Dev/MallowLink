package com.mallowlink.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mallowlink.app.data.db.entity.ChatMessageEntity
import com.mallowlink.app.data.db.entity.ChatSessionEntity
import com.mallowlink.app.data.db.entity.DocumentChunkEntity
import com.mallowlink.app.data.db.entity.IndexedDocumentEntity
import com.mallowlink.app.data.db.entity.IndexedSourceEntity
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// Source DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: IndexedSourceEntity)

    @Update
    suspend fun update(source: IndexedSourceEntity)

    @Delete
    suspend fun delete(source: IndexedSourceEntity)

    @Query("SELECT * FROM indexed_sources ORDER BY display_name ASC")
    fun observeAll(): Flow<List<IndexedSourceEntity>>

    @Query("SELECT * FROM indexed_sources WHERE id = :id")
    suspend fun getById(id: String): IndexedSourceEntity?

    @Query("SELECT * FROM indexed_sources WHERE is_enabled = 1")
    suspend fun getEnabled(): List<IndexedSourceEntity>

    @Query("UPDATE indexed_sources SET last_synced_at = :ts, document_count = :count WHERE id = :id")
    suspend fun updateSyncMeta(id: String, ts: Long, count: Int)
}

// ─────────────────────────────────────────────────────────────────────────────
// Document DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doc: IndexedDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(docs: List<IndexedDocumentEntity>)

    @Delete
    suspend fun delete(doc: IndexedDocumentEntity)

    @Query("DELETE FROM indexed_documents WHERE source_id = :sourceId")
    suspend fun deleteBySource(sourceId: String)

    @Query("DELETE FROM indexed_documents WHERE id = :docId")
    suspend fun deleteById(docId: String)

    @Query("SELECT * FROM indexed_documents WHERE source_id = :sourceId ORDER BY display_name ASC")
    fun observeBySource(sourceId: String): Flow<List<IndexedDocumentEntity>>

    @Query("SELECT * FROM indexed_documents ORDER BY indexed_at DESC")
    fun observeAll(): Flow<List<IndexedDocumentEntity>>

    @Query("SELECT * FROM indexed_documents WHERE id = :id")
    suspend fun getById(id: String): IndexedDocumentEntity?

    @Query("SELECT * FROM indexed_documents WHERE uri_string = :uri")
    suspend fun getByUri(uri: String): IndexedDocumentEntity?

    @Query("SELECT COUNT(*) FROM indexed_documents WHERE source_id = :sourceId")
    suspend fun countBySource(sourceId: String): Int

    @Query("SELECT * FROM indexed_documents WHERE last_modified > :since")
    suspend fun getModifiedSince(since: Long): List<IndexedDocumentEntity>
}

// ─────────────────────────────────────────────────────────────────────────────
// Chunk DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chunk: DocumentChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chunks: List<DocumentChunkEntity>)

    @Query("DELETE FROM document_chunks WHERE document_id = :docId")
    suspend fun deleteByDocument(docId: String)

    @Query("SELECT * FROM document_chunks WHERE document_id = :docId ORDER BY chunk_index ASC")
    suspend fun getByDocument(docId: String): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE id = :chunkId")
    suspend fun getById(chunkId: String): DocumentChunkEntity?

    /**
     * Returns ALL chunks with their embeddings from enabled sources.
     * Called once by the VectorStore when it (re-)builds its in-memory ANN index.
     */
    @Query("""
        SELECT dc.*
        FROM document_chunks dc
        INNER JOIN indexed_sources s ON dc.source_id = s.id
        WHERE s.is_enabled = 1
    """)
    suspend fun getAllFromEnabledSources(): List<DocumentChunkEntity>

    /**
     * Filtered variant for @-prefixes in queries.
     */
    @Query("""
        SELECT dc.*
        FROM document_chunks dc
        INNER JOIN indexed_documents d ON dc.document_id = d.id
        INNER JOIN indexed_sources s ON dc.source_id = s.id
        WHERE s.is_enabled = 1
          AND (:mimeFilter IS NULL OR d.mime_type LIKE :mimeFilter)
          AND (:sourceName IS NULL OR s.display_name = :sourceName)
        ORDER BY dc.document_id, dc.chunk_index
    """)
    suspend fun getFiltered(mimeFilter: String?, sourceName: String?): List<DocumentChunkEntity>

    @Query("SELECT COUNT(*) FROM document_chunks WHERE document_id = :docId")
    suspend fun countByDocument(docId: String): Int
}

// ─────────────────────────────────────────────────────────────────────────────
// Chat DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface ChatDao {
    // Sessions
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ChatSessionEntity)

    @Delete
    suspend fun deleteSession(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions ORDER BY updated_at DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getSession(id: String): ChatSessionEntity?

    @Query("UPDATE chat_sessions SET updated_at = :ts, title = :title WHERE id = :id")
    suspend fun updateSessionMeta(id: String, ts: Long, title: String)

    // Messages
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC")
    fun observeMessages(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getMessage(id: String): ChatMessageEntity?

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Transaction
    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getSessionWithMessages(id: String): ChatSessionEntity?
}
