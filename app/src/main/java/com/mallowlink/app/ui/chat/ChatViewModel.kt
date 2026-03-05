package com.mallowlink.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mallowlink.app.data.rag.RagEvent
import com.mallowlink.app.data.rag.RagPipeline
import com.mallowlink.app.data.repository.ChatRepository
import com.mallowlink.app.domain.model.ChatMessage
import com.mallowlink.app.domain.model.ChatSession
import com.mallowlink.app.domain.model.Citation
import com.mallowlink.app.domain.model.FilterType
import com.mallowlink.app.domain.model.MessageRole
import com.mallowlink.app.domain.model.SourceFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// ChatViewModel
// ─────────────────────────────────────────────────────────────────────────────

data class ChatUiState(
    val session: ChatSession? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val pendingCitations: List<Citation> = emptyList(),
    val activeFilter: SourceFilter = SourceFilter.ALL,
    val inputText: String = "",
    val error: String? = null,
    /** Streamed text for the in-progress assistant bubble */
    val streamingText: String = "",
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val ragPipeline: RagPipeline,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String? = savedStateHandle["sessionId"]

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        if (sessionId != null) {
            observeMessages(sessionId)
        }
    }

    private fun observeMessages(sid: String) {
        viewModelScope.launch {
            chatRepository.observeMessages(sid).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
        // Parse @filter tags
        val filter = parseFilterFromQuery(text)
        _uiState.update { it.copy(activeFilter = filter) }
    }

    fun onSendMessage() {
        val query = _uiState.value.inputText.trim()
        if (query.isBlank() || _uiState.value.isGenerating) return

        viewModelScope.launch {
            // Ensure we have a session
            val sid = ensureSession()

            // Save user message
            chatRepository.saveMessage(sid, MessageRole.USER, query)
            _uiState.update { it.copy(inputText = "", isGenerating = true, streamingText = "", error = null) }

            // Build history (last 4 pairs)
            val history = buildHistory()

            // Start RAG pipeline
            var citations = emptyList<Citation>()
            val streamBuffer = StringBuilder()

            ragPipeline.query(
                userQuery = query,
                filter = _uiState.value.activeFilter,
                history = history,
                topK = 5,
            ).collect { event ->
                when (event) {
                    is RagEvent.CitationsReady -> {
                        citations = event.citations
                        _uiState.update { it.copy(pendingCitations = citations) }
                    }
                    is RagEvent.Token -> {
                        streamBuffer.append(event.text)
                        _uiState.update { it.copy(streamingText = streamBuffer.toString()) }
                    }
                    is RagEvent.Done -> {
                        // Persist the complete answer
                        chatRepository.saveMessage(
                            sessionId = sid,
                            role = MessageRole.ASSISTANT,
                            content = streamBuffer.toString(),
                            citations = citations,
                        )
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                streamingText = "",
                                pendingCitations = emptyList(),
                            )
                        }
                    }
                    is RagEvent.Error -> {
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                streamingText = "",
                                error = event.message,
                            )
                        }
                        chatRepository.saveMessage(
                            sessionId = sid,
                            role = MessageRole.ASSISTANT,
                            content = "",
                            error = event.message,
                        )
                    }
                }
            }
        }
    }

    fun onShareIntoChat(uriString: String) {
        _uiState.update { it.copy(inputText = "Summarise this file: $uriString") }
    }

    fun onFilterSelected(filter: SourceFilter) {
        _uiState.update { it.copy(activeFilter = filter) }
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }

    private suspend fun ensureSession(): String {
        val existing = _uiState.value.session
        if (existing != null) return existing.id
        val session = chatRepository.createSession("New chat", _uiState.value.activeFilter)
        _uiState.update { it.copy(session = session) }
        return session.id
    }

    private fun buildHistory(): List<Pair<String, String>> {
        val msgs = _uiState.value.messages
        val pairs = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < msgs.size - 1) {
            val cur = msgs[i]
            val next = msgs[i + 1]
            if (cur.role == MessageRole.USER && next.role == MessageRole.ASSISTANT) {
                pairs.add(cur.content to next.content)
                i += 2
            } else {
                i++
            }
        }
        return pairs
    }

    companion object {
        /** Extracts @-filter from query text, e.g. "@pdf tell me about…" */
        fun parseFilterFromQuery(query: String): SourceFilter {
            val token = Regex("@(\\w+)").find(query)?.groupValues?.getOrNull(1)?.lowercase()
            return when (token) {
                "pdf"    -> SourceFilter.pdf()
                "notes"  -> SourceFilter.notes()
                "images", "image", "screenshots" -> SourceFilter.images()
                null     -> SourceFilter.ALL
                else     -> SourceFilter.folder(token)
            }
        }
    }
}
