package com.mallowlink.app.calls.linking

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import com.mallowlink.app.calls.db.dao.CallSummaryDao
import com.mallowlink.app.calls.db.dao.LinkedResourceDao
import com.mallowlink.app.calls.db.entity.CallSummaryEntity
import com.mallowlink.app.calls.db.entity.LinkedResourceEntity
import com.mallowlink.app.data.model.EmbeddingManager
import com.mallowlink.app.data.rag.VectorStore
import com.mallowlink.app.domain.model.SourceFilter
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// SmartLinker
//
// After summarisation, auto-suggests resources already in the MallowLink index
// that are semantically related to the call/meeting summary.
//
// Sources checked:
//   1. Indexed documents (via VectorStore cosine similarity on summary text)
//   2. Calendar events   (via ContentProvider query; no additional embedding)
//   3. (Future) Indexed messages
// ─────────────────────────────────────────────────────────────────────────────

private const val LINKING_TOP_K        = 5
private const val CALENDAR_WINDOW_DAYS = 7L     // look ±7 days from call date
private const val MIN_RELEVANCE_SCORE  = 0.35f  // suppress weak matches

@Singleton
class SmartLinker @Inject constructor(
    private val context: Context,
    private val embeddingManager: EmbeddingManager,
    private val vectorStore: VectorStore,
    private val summaryDao: CallSummaryDao,
    private val linkedResourceDao: LinkedResourceDao,
) {
    /**
     * Finds related documents + calendar events and persists them as
     * [LinkedResourceEntity] rows under [summary.id].
     */
    suspend fun suggestLinks(summary: CallSummaryEntity) {
        val resources = mutableListOf<LinkedResourceEntity>()

        // ── 1. Related indexed documents ──────────────────────────────────────
        val queryText = "${summary.overview} ${extractPlainActionText(summary)}"
        embeddingManager.init()
        val queryEmbedding = embeddingManager.embed(queryText)
        if (queryEmbedding != null) {
            val docMatches = vectorStore.search(
                queryEmbedding = queryEmbedding,
                topK           = LINKING_TOP_K,
                filter         = SourceFilter.ALL,
            )
            docMatches
                .filter { it.score >= MIN_RELEVANCE_SCORE }
                .forEach { match ->
                    resources.add(
                        LinkedResourceEntity(
                            id            = UUID.randomUUID().toString(),
                            summaryId     = summary.id,
                            resourceType  = "DOCUMENT",
                            resourceId    = match.document.id,
                            displayTitle  = match.document.displayName,
                            uriString     = match.document.uriString,
                            relevanceScore = match.score,
                        )
                    )
                }
        }

        // ── 2. Calendar events near the call time ─────────────────────────────
        try {
            val calEvents = queryNearbyCalendarEvents(summary.generatedAt)
            calEvents.forEach { event ->
                val titleScore = coarseTextSimilarity(queryText, event.title)
                if (titleScore >= 0.2f) {
                    resources.add(
                        LinkedResourceEntity(
                            id            = UUID.randomUUID().toString(),
                            summaryId     = summary.id,
                            resourceType  = "CALENDAR_EVENT",
                            resourceId    = event.eventId.toString(),
                            displayTitle  = event.title,
                            uriString     = ContentUris
                                .withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
                                .toString(),
                            relevanceScore = titleScore,
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Timber.w("Calendar permission not granted – skipping event linking")
        }

        if (resources.isNotEmpty()) {
            linkedResourceDao.insertAll(resources)
            // Embed the linked resource IDs back into the summary JSON
            val updatedSummary = summary.copy(
                linkedResourcesJson = kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.json.Json.serializersModule,
                    kotlinx.serialization.serializer(),
                    resources.map { it.resourceId }
                )
            )
            summaryDao.upsert(updatedSummary)
            Timber.i("SmartLinker: ${resources.size} links for summary ${summary.id}")
        }
    }

    // ── Calendar query ─────────────────────────────────────────────────────────

    private data class CalEvent(val eventId: Long, val title: String, val startMs: Long)

    private fun queryNearbyCalendarEvents(refTimeMs: Long): List<CalEvent> {
        val windowMs = TimeUnit.DAYS.toMillis(CALENDAR_WINDOW_DAYS)
        val start    = refTimeMs - windowMs
        val end      = refTimeMs + windowMs
        val events   = mutableListOf<CalEvent>()

        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
            ),
            "${CalendarContract.Events.DTSTART} BETWEEN ? AND ?",
            arrayOf(start.toString(), end.toString()),
            "${CalendarContract.Events.DTSTART} ASC",
        ) ?: return emptyList()

        cursor.use {
            while (it.moveToNext()) {
                events.add(
                    CalEvent(
                        eventId = it.getLong(0),
                        title   = it.getString(1) ?: "",
                        startMs = it.getLong(2),
                    )
                )
            }
        }
        return events
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Cheap word-overlap similarity (Jaccard) — no embedding needed for
     * short calendar event titles.
     */
    private fun coarseTextSimilarity(a: String, b: String): Float {
        val aWords = a.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val bWords = b.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (aWords.isEmpty() || bWords.isEmpty()) return 0f
        val intersection = aWords.intersect(bWords).size.toFloat()
        val union = (aWords + bWords).size.toFloat()
        return intersection / union
    }

    private fun extractPlainActionText(summary: CallSummaryEntity): String {
        return try {
            val items = kotlinx.serialization.json.Json.decodeFromString<List<com.mallowlink.app.calls.summary.ActionItem>>(
                summary.actionItemsJson
            )
            items.joinToString(" ") { it.description }
        } catch (_: Exception) { "" }
    }
}

// CalendarBridge helper: requests READ_CALENDAR at runtime
object CalendarPermissionHelper {
    const val PERMISSION = android.Manifest.permission.READ_CALENDAR
}
