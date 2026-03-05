package com.mallowlink.app.domain.model

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// Core domain models (pure Kotlin — no Android / Room dependencies)
// ─────────────────────────────────────────────────────────────────────────────

/** A data-source registered by the user (folder, SAF tree, or import). */
@Serializable
data class IndexedSource(
    val id: String,
    val displayName: String,
    val uriString: String,
    val type: SourceType,
    val isEnabled: Boolean = true,
    val lastSyncedAt: Long = 0L,
    val documentCount: Int = 0,
)

@Serializable
enum class SourceType {
    LOCAL_FOLDER,
    SAF_TREE,
    IMPORTED_MBOX,
    IMPORTED_WHATSAPP,
    IMPORTED_SMS,
}

/** Represents a file that has been parsed and is tracked in the index. */
@Serializable
data class IndexedDocument(
    val id: String,
    val sourceId: String,
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val fileType: FileType,
    val fileSizeBytes: Long,
    val lastModified: Long,
    val indexedAt: Long,
    val chunkCount: Int = 0,
    val thumbnailPath: String? = null,
)

@Serializable
enum class FileType {
    PDF, DOCX, TXT, MARKDOWN, IMAGE, EMAIL_MBOX, WHATSAPP_EXPORT, SMS_EXPORT, UNKNOWN
}

/** A text chunk extracted from a document, stored alongside its embedding. */
@Serializable
data class DocumentChunk(
    val id: String,
    val documentId: String,
    val sourceId: String,
    /** 0-based page or section index */
    val pageIndex: Int,
    val chunkIndex: Int,
    val text: String,
    val embedding: FloatArray = FloatArray(0),
    /** Character offset of this chunk within the original document text */
    val startChar: Int = 0,
    val endChar: Int = 0,
)

/** A chat session containing an ordered list of messages. */
@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val activeSourceFilter: SourceFilter = SourceFilter.ALL,
)

@Serializable
data class SourceFilter(
    val type: FilterType = FilterType.ALL,
    /** For FilterType.FOLDER – folder name / source id. */
    val value: String = "",
) {
    companion object {
        val ALL = SourceFilter(FilterType.ALL)
        fun pdf() = SourceFilter(FilterType.MIME_TYPE, "application/pdf")
        fun notes() = SourceFilter(FilterType.SOURCE_TYPE, SourceType.LOCAL_FOLDER.name)
        fun images() = SourceFilter(FilterType.MIME_TYPE, "image/*")
        fun folder(name: String) = SourceFilter(FilterType.FOLDER, name)
    }
}

@Serializable
enum class FilterType { ALL, MIME_TYPE, SOURCE_TYPE, FOLDER }

/** A single chat turn (user query or assistant answer). */
@Serializable
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val citations: List<Citation> = emptyList(),
    val timestamp: Long,
    val isStreaming: Boolean = false,
    val error: String? = null,
)

@Serializable
enum class MessageRole { USER, ASSISTANT, SYSTEM }

/** A reference from an assistant answer back to a specific document chunk. */
@Serializable
data class Citation(
    val index: Int,         // [1], [2], … inline citation label
    val documentId: String,
    val documentName: String,
    val pageIndex: Int,
    val chunkText: String,  // relevant excerpt
    val uriString: String,  // deep-link back to the original file
)

/** Retrieval result: a ranked chunk with its similarity score. */
data class RetrievedChunk(
    val chunk: DocumentChunk,
    val document: IndexedDocument,
    val score: Float,
)

/** App-wide indexing state reported to the UI. */
sealed class IndexingState {
    object Idle : IndexingState()
    data class Running(val currentFile: String, val progress: Int, val total: Int) : IndexingState()
    data class Error(val message: String) : IndexingState()
    object Paused : IndexingState()
}

/** Result of a RAG query before it is streamed to the chat. */
data class RagResult(
    val answer: String,
    val citations: List<Citation>,
    val retrievedChunks: List<RetrievedChunk>,
)
