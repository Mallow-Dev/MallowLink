package com.mallowlink.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mallowlink.app.domain.model.FileType
import com.mallowlink.app.domain.model.FilterType
import com.mallowlink.app.domain.model.MessageRole
import com.mallowlink.app.domain.model.SourceType

// ─────────────────────────────────────────────────────────────────────────────
// Room entities – mirror domain models but add persistence specifics
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "indexed_sources")
data class IndexedSourceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "uri_string") val uriString: String,
    @ColumnInfo(name = "type") val type: SourceType,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long = 0L,
    @ColumnInfo(name = "document_count") val documentCount: Int = 0,
)

@Entity(
    tableName = "indexed_documents",
    foreignKeys = [
        ForeignKey(
            entity = IndexedSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["source_id"]),
        Index(value = ["uri_string"], unique = true),
        Index(value = ["last_modified"]),
    ]
)
data class IndexedDocumentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "uri_string") val uriString: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "file_type") val fileType: FileType,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long,
    @ColumnInfo(name = "last_modified") val lastModified: Long,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
    @ColumnInfo(name = "chunk_count") val chunkCount: Int = 0,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
)

@Entity(
    tableName = "document_chunks",
    foreignKeys = [
        ForeignKey(
            entity = IndexedDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["document_id"]),
        Index(value = ["source_id"]),
    ]
)
data class DocumentChunkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "text") val text: String,
    /** Serialised float array – stored as BLOB for fast bulk reads */
    @ColumnInfo(name = "embedding", typeAffinity = androidx.room.ColumnInfo.BLOB)
    val embedding: ByteArray = ByteArray(0),
    @ColumnInfo(name = "start_char") val startChar: Int = 0,
    @ColumnInfo(name = "end_char") val endChar: Int = 0,
)

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "filter_type") val filterType: FilterType = FilterType.ALL,
    @ColumnInfo(name = "filter_value") val filterValue: String = "",
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["session_id"])]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "role") val role: MessageRole,
    @ColumnInfo(name = "content") val content: String,
    /** JSON-serialised List<Citation> */
    @ColumnInfo(name = "citations_json") val citationsJson: String = "[]",
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "error") val error: String? = null,
)
