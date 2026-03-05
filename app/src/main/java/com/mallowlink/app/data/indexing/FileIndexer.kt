package com.mallowlink.app.data.indexing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.mallowlink.app.data.db.dao.ChunkDao
import com.mallowlink.app.data.db.dao.DocumentDao
import com.mallowlink.app.data.db.dao.SourceDao
import com.mallowlink.app.data.db.entity.DocumentChunkEntity
import com.mallowlink.app.data.db.entity.IndexedDocumentEntity
import com.mallowlink.app.data.model.EmbeddingManager
import com.mallowlink.app.data.model.toByteArray
import com.mallowlink.app.data.rag.VectorStore
import com.mallowlink.app.domain.model.FileType
import com.mallowlink.app.domain.model.IndexingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// FileIndexer
//
// Walks a SAF tree URI or media-store folder, parses each file, generates
// embeddings, and writes chunks to Room.  Emits progress via [indexingState].
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class FileIndexer @Inject constructor(
    private val context: Context,
    private val parser: DocumentParser,
    private val embeddingManager: EmbeddingManager,
    private val sourceDao: SourceDao,
    private val documentDao: DocumentDao,
    private val chunkDao: ChunkDao,
    private val vectorStore: VectorStore,
) {
    private val _state = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingState: StateFlow<IndexingState> = _state.asStateFlow()

    private var isPaused = false

    fun pause() { isPaused = true }
    fun resume() { isPaused = false }

    /** Index (or re-index) a single SAF tree URI for the given [sourceId]. */
    suspend fun indexSource(sourceId: String, treeUri: Uri) = withContext(Dispatchers.IO) {
        Timber.i("Starting indexing for source $sourceId, uri=$treeUri")

        val files = listFilesFromSafTree(treeUri)
        var processed = 0

        for (fileEntry in files) {
            if (isPaused) {
                _state.value = IndexingState.Paused
                while (isPaused) kotlinx.coroutines.delay(500)
                _state.value = IndexingState.Running(fileEntry.displayName, processed, files.size)
            }

            _state.value = IndexingState.Running(fileEntry.displayName, processed, files.size)

            try {
                indexFile(sourceId, fileEntry)
            } catch (e: Exception) {
                Timber.e(e, "Failed to index ${fileEntry.uri}")
            }
            processed++
        }

        // Update source metadata
        val count = documentDao.countBySource(sourceId)
        sourceDao.updateSyncMeta(sourceId, System.currentTimeMillis(), count)

        vectorStore.invalidate()
        _state.value = IndexingState.Idle
        Timber.i("Indexing complete for $sourceId: $processed files")
    }

    private suspend fun indexFile(sourceId: String, file: FileEntry) {
        // Skip if file hasn't changed since last index
        val existing = documentDao.getByUri(file.uri.toString())
        if (existing != null && existing.lastModified == file.lastModified) return

        val docId = existing?.id ?: UUID.randomUUID().toString()
        val fileType = mimeTypeToFileType(file.mimeType)

        val docEntity = IndexedDocumentEntity(
            id = docId,
            sourceId = sourceId,
            uriString = file.uri.toString(),
            displayName = file.displayName,
            mimeType = file.mimeType,
            fileType = fileType,
            fileSizeBytes = file.sizeBytes,
            lastModified = file.lastModified,
            indexedAt = System.currentTimeMillis(),
        )
        documentDao.upsert(docEntity)

        // Delete stale chunks
        chunkDao.deleteByDocument(docId)

        // Parse
        val parsed = parser.parse(file.uri, file.mimeType)
        if (parsed.pages.isEmpty()) return

        val chunks = mutableListOf<DocumentChunkEntity>()

        for (page in parsed.pages) {
            val textChunks = page.text.toChunks(maxTokens = 300, overlapTokens = 50)
            textChunks.forEachIndexed { chunkIdx, chunkText ->
                if (chunkText.isBlank()) return@forEachIndexed

                val embedding = embeddingManager.embed(chunkText)
                chunks.add(
                    DocumentChunkEntity(
                        id = UUID.randomUUID().toString(),
                        documentId = docId,
                        sourceId = sourceId,
                        pageIndex = page.pageIndex,
                        chunkIndex = chunkIdx,
                        text = chunkText,
                        embedding = embedding?.toByteArray() ?: ByteArray(0),
                    )
                )
            }
        }

        chunkDao.upsertAll(chunks)
        documentDao.upsert(docEntity.copy(chunkCount = chunks.size))

        Timber.d("Indexed ${file.displayName}: ${chunks.size} chunks")
    }

    // ── SAF tree walker ──────────────────────────────────────────────────────

    private fun listFilesFromSafTree(treeUri: Uri): List<FileEntry> {
        val result = mutableListOf<FileEntry>()
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        walkSafDirectory(treeUri, docId, result)
        return result.filter { it.sizeBytes > 0 && it.sizeBytes < MAX_FILE_BYTES }
    }

    private fun walkSafDirectory(treeUri: Uri, dirId: String, result: MutableList<FileEntry>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null
        ) ?: return

        cursor.use {
            while (it.moveToNext()) {
                val childId = it.getString(0)
                val name = it.getString(1) ?: continue
                val mime = it.getString(2) ?: "application/octet-stream"
                val size = it.getLong(3)
                val modified = it.getLong(4)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkSafDirectory(treeUri, childId, result)
                } else if (isSupportedMime(mime) || isSupportedExtension(name)) {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    result.add(FileEntry(fileUri, name, mime, size, modified))
                }
            }
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 50L * 1024 * 1024  // 50 MB cap per file

        fun isSupportedMime(mime: String) = mime in setOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown",
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "image/heic",
            "message/rfc822",
        )

        fun isSupportedExtension(name: String): Boolean {
            val ext = name.substringAfterLast('.').lowercase()
            return ext in setOf("pdf", "docx", "txt", "md", "markdown", "jpg", "jpeg", "png",
                "webp", "heic", "mbox", "eml")
        }

        fun mimeTypeToFileType(mime: String): FileType = when {
            mime == "application/pdf" -> FileType.PDF
            mime.contains("wordprocessingml") -> FileType.DOCX
            mime == "text/markdown" -> FileType.MARKDOWN
            mime.startsWith("image/") -> FileType.IMAGE
            mime == "message/rfc822" -> FileType.EMAIL_MBOX
            mime == "text/plain" -> FileType.TXT
            else -> FileType.UNKNOWN
        }
    }
}

data class FileEntry(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
)
