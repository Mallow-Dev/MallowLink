package com.mallowlink.app.calls.summary

import com.mallowlink.app.calls.db.dao.CallSummaryDao
import com.mallowlink.app.calls.db.dao.TranscriptSegmentDao
import com.mallowlink.app.calls.db.entity.CallSummaryEntity
import com.mallowlink.app.calls.db.entity.TranscriptSegmentEntity
import com.mallowlink.app.data.model.LlmEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// SummarisationPipeline
//
// Processes a completed transcript into:
//   • Overview paragraph  (2-4 sentences)
//   • Action items        (who does what, by when)
//   • Key decisions       (with timestamp back-links)
//
// Uses the same on-device LlmEngine (Phi-2 / Qwen2 / TinyLlama) as the
// main RAG chat feature.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ActionItem(
    val description: String,
    val owner: String = "Unknown",
    val dueHint: String = "",
    /** Segment IDs that mention this action item */
    val segmentIds: List<String> = emptyList(),
)

@Serializable
data class KeyDecision(
    val summary: String,
    val startSec: Float,
    val endSec: Float,
    /** segment IDs */
    val segmentIds: List<String> = emptyList(),
)

@Singleton
class SummarisationPipeline @Inject constructor(
    private val llmEngine: LlmEngine,
    private val transcriptDao: TranscriptSegmentDao,
    private val summaryDao: CallSummaryDao,
    private val json: Json,
) {
    /**
     * Generates and persists a [CallSummaryEntity] for [sessionId].
     * Returns the entity on success, null on failure.
     */
    suspend fun summarise(sessionId: String): CallSummaryEntity? {
        val segments = transcriptDao.getBySession(sessionId)
        if (segments.isEmpty()) {
            Timber.w("No transcript segments for $sessionId – skipping summarisation")
            return null
        }

        val fullTranscript = buildTranscriptText(segments)
        Timber.d("Summarising ${segments.size} segments (${fullTranscript.length} chars)")

        return try {
            val overview    = generateOverview(fullTranscript)
            val actionItems = extractActionItems(fullTranscript, segments)
            val decisions   = extractKeyDecisions(fullTranscript, segments)

            val entity = CallSummaryEntity(
                id                = UUID.randomUUID().toString(),
                sessionId         = sessionId,
                overview          = overview,
                actionItemsJson   = json.encodeToString(actionItems),
                keyDecisionsJson  = json.encodeToString(decisions),
                generatedAt       = System.currentTimeMillis(),
            )
            summaryDao.upsert(entity)
            Timber.i("Summary persisted for $sessionId")
            entity
        } catch (e: Exception) {
            Timber.e(e, "Summarisation failed for $sessionId")
            null
        }
    }

    // ── Generation helpers ────────────────────────────────────────────────────

    private suspend fun generateOverview(transcript: String): String {
        val prompt = buildString {
            appendLine(SYSTEM_PROMPT)
            appendLine()
            appendLine("## Transcript")
            appendLine(transcript.take(3000))
            appendLine()
            appendLine("## Task: Write a 2-4 sentence overview of this conversation.")
            appendLine("Overview:")
        }
        return collectLlmOutput(prompt).trim()
    }

    private suspend fun extractActionItems(
        transcript: String,
        segments: List<TranscriptSegmentEntity>,
    ): List<ActionItem> {
        val prompt = buildString {
            appendLine(SYSTEM_PROMPT)
            appendLine()
            appendLine("## Transcript")
            appendLine(transcript.take(3000))
            appendLine()
            appendLine("""
                ## Task: List every action item from this conversation.
                Format each on a new line as: "• [Owner]: [Description] (by [when])"
                If no owner is clear, use "Unknown". If no deadline, omit the "by" clause.
                Action items:
            """.trimIndent())
        }
        val raw = collectLlmOutput(prompt).trim()
        return parseActionItems(raw, segments)
    }

    private suspend fun extractKeyDecisions(
        transcript: String,
        segments: List<TranscriptSegmentEntity>,
    ): List<KeyDecision> {
        val prompt = buildString {
            appendLine(SYSTEM_PROMPT)
            appendLine()
            appendLine("## Transcript")
            appendLine(transcript.take(3000))
            appendLine()
            appendLine("""
                ## Task: List the key decisions made in this conversation.
                Format each as: "• [Decision summary]"
                Key decisions:
            """.trimIndent())
        }
        val raw = collectLlmOutput(prompt).trim()
        return parseKeyDecisions(raw, segments)
    }

    private suspend fun collectLlmOutput(prompt: String): String {
        val sb = StringBuilder()
        llmEngine.generate(prompt).collect { token ->
            if (token == LlmEngine.DONE_TOKEN) return@collect
            sb.append(token)
        }
        return sb.toString()
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private fun parseActionItems(
        raw: String,
        segments: List<TranscriptSegmentEntity>,
    ): List<ActionItem> {
        return raw.lines()
            .filter { it.trimStart().startsWith("•") || it.trimStart().startsWith("-") }
            .mapNotNull { line ->
                val clean = line.trimStart('•', '-', ' ')
                if (clean.isBlank()) return@mapNotNull null
                val ownerMatch = Regex("^\\[?([^]:]+)]?:(.+)").find(clean)
                if (ownerMatch != null) {
                    val owner = ownerMatch.groupValues[1].trim()
                    val desc  = ownerMatch.groupValues[2].trim()
                    val dueMatch = Regex("\\(by (.+)\\)").find(desc)
                    ActionItem(
                        description = desc.replace(dueMatch?.value ?: "", "").trim(),
                        owner = owner,
                        dueHint = dueMatch?.groupValues?.get(1) ?: "",
                        segmentIds = findRelevantSegments(desc, segments),
                    )
                } else {
                    ActionItem(
                        description = clean,
                        segmentIds  = findRelevantSegments(clean, segments),
                    )
                }
            }
    }

    private fun parseKeyDecisions(
        raw: String,
        segments: List<TranscriptSegmentEntity>,
    ): List<KeyDecision> {
        return raw.lines()
            .filter { it.trimStart().startsWith("•") || it.trimStart().startsWith("-") }
            .mapNotNull { line ->
                val clean = line.trimStart('•', '-', ' ')
                if (clean.isBlank()) return@mapNotNull null
                val relevant = findRelevantSegments(clean, segments)
                val startSeg = segments.firstOrNull { it.id in relevant }
                val endSeg   = segments.lastOrNull  { it.id in relevant }
                KeyDecision(
                    summary    = clean,
                    startSec   = startSeg?.startSec ?: 0f,
                    endSec     = endSeg?.endSec ?: 0f,
                    segmentIds = relevant,
                )
            }
    }

    /** Returns segment IDs whose text contains any word from [query]. */
    private fun findRelevantSegments(query: String, segments: List<TranscriptSegmentEntity>): List<String> {
        val words = query.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        return segments.filter { seg ->
            val segLower = seg.text.lowercase()
            words.any { w -> segLower.contains(w) }
        }.map { it.id }.take(3)
    }

    private fun buildTranscriptText(segments: List<TranscriptSegmentEntity>): String =
        segments.joinToString("\n") { seg ->
            val ts = formatTimestamp(seg.startSec)
            "[${seg.speaker} @ $ts] ${seg.text}"
        }

    private fun formatTimestamp(secs: Float): String {
        val m = (secs / 60).toInt()
        val s = (secs % 60).toInt()
        return "%d:%02d".format(m, s)
    }

    companion object {
        private const val SYSTEM_PROMPT = """You are MallowLink, a private on-device AI assistant.
Analyse the provided meeting/call transcript and respond concisely.
Focus only on information present in the transcript.
Do not invent names, dates, or facts not mentioned."""
    }
}
