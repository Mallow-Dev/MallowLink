package com.mallowlink.app.data.rag

import com.mallowlink.app.data.model.EmbeddingManager
import com.mallowlink.app.data.model.LlmEngine
import com.mallowlink.app.data.model.PromptBuilder
import com.mallowlink.app.domain.model.Citation
import com.mallowlink.app.domain.model.RagResult
import com.mallowlink.app.domain.model.RetrievedChunk
import com.mallowlink.app.domain.model.SourceFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// RAG Pipeline
//
// Orchestrates: query embedding → retrieval → prompt assembly → LLM generation
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class RagPipeline @Inject constructor(
    private val embeddingManager: EmbeddingManager,
    private val vectorStore: VectorStore,
    private val llmEngine: LlmEngine,
) {
    /**
     * Executes a RAG query and streams answer tokens.
     *
     * Emits:
     *  - [RagEvent.CitationsReady] once retrieval is complete (before generation starts)
     *  - [RagEvent.Token] for each generated token
     *  - [RagEvent.Done] when generation is complete
     *  - [RagEvent.Error] if something goes wrong
     */
    fun query(
        userQuery: String,
        filter: SourceFilter = SourceFilter.ALL,
        history: List<Pair<String, String>> = emptyList(),
        topK: Int = 5,
    ): Flow<RagEvent> = flow {
        try {
            // ── Step 1: Embed the query ───────────────────────────────────────
            val queryEmbedding = embeddingManager.embed(userQuery)
            if (queryEmbedding == null) {
                emit(RagEvent.Error("Embedding model not ready. Please wait for initialisation."))
                return@flow
            }

            // ── Step 2: Retrieve relevant chunks ─────────────────────────────
            val retrieved = vectorStore.search(queryEmbedding, topK = topK, filter = filter)
            Timber.d("Retrieved ${retrieved.size} chunks for query: $userQuery")

            if (retrieved.isEmpty()) {
                emit(RagEvent.CitationsReady(emptyList()))
                emit(RagEvent.Token("I couldn't find any relevant information in your indexed documents. "))
                emit(RagEvent.Token("Try indexing more files or broadening your search filter."))
                emit(RagEvent.Done(""))
                return@flow
            }

            // ── Step 3: Build citation list ───────────────────────────────────
            val citations = retrieved.mapIndexed { i, r ->
                Citation(
                    index = i + 1,
                    documentId = r.document.id,
                    documentName = r.document.displayName,
                    pageIndex = r.chunk.pageIndex,
                    chunkText = r.chunk.text.take(200),
                    uriString = r.document.uriString,
                )
            }
            emit(RagEvent.CitationsReady(citations))

            // ── Step 4: Build prompt ──────────────────────────────────────────
            val contextBlock = buildContextBlock(retrieved)
            val prompt = PromptBuilder.buildRagPrompt(
                query = userQuery,
                context = contextBlock,
                history = history,
            )

            // ── Step 5: Stream generation ─────────────────────────────────────
            val fullAnswer = StringBuilder()
            llmEngine.generate(prompt).collect { token ->
                fullAnswer.append(token)
                emit(RagEvent.Token(token))
            }

            emit(RagEvent.Done(fullAnswer.toString()))

        } catch (e: Exception) {
            Timber.e(e, "RAG pipeline error")
            emit(RagEvent.Error(e.message ?: "Unknown error"))
        }
    }

    private fun buildContextBlock(chunks: List<RetrievedChunk>): String = buildString {
        chunks.forEachIndexed { i, r ->
            appendLine("[${i + 1}] **${r.document.displayName}** (page ${r.chunk.pageIndex + 1})")
            appendLine(r.chunk.text.take(500))
            appendLine()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Streaming event types
// ─────────────────────────────────────────────────────────────────────────────

sealed class RagEvent {
    data class CitationsReady(val citations: List<Citation>) : RagEvent()
    data class Token(val text: String) : RagEvent()
    data class Done(val fullText: String) : RagEvent()
    data class Error(val message: String) : RagEvent()
}
