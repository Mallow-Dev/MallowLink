package com.mallowlink.app.calls.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mallowlink.app.calls.db.dao.CallSummaryDao
import com.mallowlink.app.calls.db.dao.LinkedResourceDao
import com.mallowlink.app.calls.db.dao.RecordingSessionDao
import com.mallowlink.app.calls.db.dao.TranscriptSegmentDao
import com.mallowlink.app.calls.db.entity.LinkedResourceEntity
import com.mallowlink.app.calls.db.entity.ProcessingState
import com.mallowlink.app.calls.db.entity.TranscriptSegmentEntity
import com.mallowlink.app.calls.summary.ActionItem
import com.mallowlink.app.calls.summary.KeyDecision
import com.mallowlink.app.util.crypto.AudioEncryption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// CallSummaryViewModel
// ─────────────────────────────────────────────────────────────────────────────

data class CallSummaryUiState(
    val sessionId: String        = "",
    val displayTitle: String     = "",
    val sessionTypeLabel: String = "",
    val formattedDate: String    = "",
    val formattedDuration: String= "",
    val overview: String         = "",
    val actionItems: List<ActionItem>      = emptyList(),
    val keyDecisions: List<KeyDecision>    = emptyList(),
    val linkedResources: List<LinkedResourceEntity> = emptyList(),
    val transcriptSegments: List<TranscriptSegmentEntity> = emptyList(),
    val isLoading: Boolean       = true,
    val isProcessing: Boolean    = false,
    val processingLabel: String  = "",
    val error: String?           = null,
)

@HiltViewModel
class CallSummaryViewModel @Inject constructor(
    private val sessionDao: RecordingSessionDao,
    private val summaryDao: CallSummaryDao,
    private val transcriptDao: TranscriptSegmentDao,
    private val linkedResourceDao: LinkedResourceDao,
    private val encryption: AudioEncryption,
    private val json: Json,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    private val _uiState = MutableStateFlow(CallSummaryUiState(sessionId = sessionId))
    val uiState: StateFlow<CallSummaryUiState> = _uiState.asStateFlow()

    init { observeData() }

    private fun observeData() {
        viewModelScope.launch {
            val session = sessionDao.getById(sessionId) ?: run {
                _uiState.update { it.copy(isLoading = false, error = "Session not found") }
                return@launch
            }

            combine(
                summaryDao.observeBySession(sessionId),
                transcriptDao.observeBySession(sessionId),
            ) { summary, segments -> summary to segments }
                .collect { (summary, segments) ->
                    val processing = session.state in setOf(
                        ProcessingState.TRANSCRIBING, ProcessingState.SUMMARISING
                    )
                    _uiState.update { state ->
                        state.copy(
                            displayTitle   = session.displayTitle,
                            sessionTypeLabel = if (session.sessionType == "CALL") "Call" else "Meeting",
                            formattedDate  = formatDate(session.startedAt),
                            formattedDuration = formatDuration(session.durationSecs),
                            overview       = summary?.overview ?: "",
                            actionItems    = parseActionItems(summary?.actionItemsJson),
                            keyDecisions   = parseKeyDecisions(summary?.keyDecisionsJson),
                            transcriptSegments = segments,
                            isLoading      = false,
                            isProcessing   = processing,
                            processingLabel = when (session.state) {
                                ProcessingState.TRANSCRIBING -> "Transcribing audio…"
                                ProcessingState.SUMMARISING  -> "Generating summary…"
                                else -> ""
                            },
                            error = if (session.state == ProcessingState.ERROR) session.errorMessage else null,
                        )
                    }

                    // Load linked resources
                    if (summary != null) {
                        linkedResourceDao.observeBySummary(summary.id).collect { links ->
                            _uiState.update { it.copy(linkedResources = links) }
                        }
                    }
                }
        }
    }

    fun acceptLink(resourceId: String) {
        viewModelScope.launch { linkedResourceDao.setUserAccepted(resourceId, true) }
    }

    fun dismissLink(resourceId: String) {
        viewModelScope.launch { linkedResourceDao.setUserAccepted(resourceId, false) }
    }

    /** Delete only the AI summary (keep audio + transcript). */
    fun deleteSummary() {
        viewModelScope.launch { summaryDao.deleteBySession(sessionId) }
    }

    /** Delete the encrypted audio file (keep transcript + summary). */
    fun deleteRecording() {
        viewModelScope.launch {
            val session = sessionDao.getById(sessionId) ?: return@launch
            session.audioPath?.let { path ->
                val file = File(path)
                encryption.secureDelete(file)
                encryption.destroyKey(session.keyAlias)
            }
            sessionDao.forgetAudio(sessionId)
        }
    }

    /** Cryptographically wipe all data for this session. */
    fun deleteAll() {
        viewModelScope.launch {
            val session = sessionDao.getById(sessionId) ?: return@launch
            session.audioPath?.let { path ->
                encryption.secureDelete(File(path))
            }
            encryption.destroyKey(session.keyAlias)
            // Cascade: deletes transcripts, summary, linked resources
            sessionDao.deleteById(sessionId)
        }
    }

    // ── Parsers ────────────────────────────────────────────────────────────────

    private fun parseActionItems(jsonStr: String?): List<ActionItem> = try {
        jsonStr?.let { json.decodeFromString<List<ActionItem>>(it) } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun parseKeyDecisions(jsonStr: String?): List<KeyDecision> = try {
        jsonStr?.let { json.decodeFromString<List<KeyDecision>>(it) } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    // ── Formatters ─────────────────────────────────────────────────────────────

    private val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    private fun formatDate(ms: Long) = dateFmt.format(Date(ms))
    private fun formatDuration(secs: Int): String {
        val m = secs / 60; val s = secs % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
