package com.mallowlink.app.calls.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────────────────────
// Room entities for the Calls & Meetings summarisation feature
// ─────────────────────────────────────────────────────────────────────────────

/** A single captured audio session (phone call or in-person meeting). */
@Entity(tableName = "recording_sessions")
data class RecordingSessionEntity(
    @PrimaryKey val id: String,
    /** Epoch millis when capture started. */
    @ColumnInfo(name = "started_at") val startedAt: Long,
    /** Epoch millis when capture ended; null while still recording. */
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    /** CALL or MEETING */
    @ColumnInfo(name = "session_type") val sessionType: String,
    /** AES-GCM encrypted audio file path (within app private dir). Null = deleted. */
    @ColumnInfo(name = "audio_path") val audioPath: String? = null,
    /** Encrypted Base64-encoded AES key, wrapped by Android Keystore. */
    @ColumnInfo(name = "key_alias") val keyAlias: String,
    /** Caller number / meeting title — shown in UI but NOT included in analytics. */
    @ColumnInfo(name = "display_title") val displayTitle: String,
    /** Phone number sha-256 hash for dedup; null for meetings. */
    @ColumnInfo(name = "caller_hash") val callerHash: String? = null,
    /** Human-readable duration in seconds. */
    @ColumnInfo(name = "duration_secs") val durationSecs: Int = 0,
    /** Processing state: PENDING | TRANSCRIBING | SUMMARISING | DONE | ERROR | DELETED */
    @ColumnInfo(name = "state") val state: String = ProcessingState.PENDING,
    /** True once user confirmed consent for this specific session. */
    @ColumnInfo(name = "consent_given") val consentGiven: Boolean = false,
    /** True if the counterparty was notified (for call recording compliance). */
    @ColumnInfo(name = "counterparty_notified") val counterpartyNotified: Boolean = false,
    /** Error message if state == ERROR */
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
)

object ProcessingState {
    const val PENDING        = "PENDING"
    const val TRANSCRIBING   = "TRANSCRIBING"
    const val SUMMARISING    = "SUMMARISING"
    const val DONE           = "DONE"
    const val ERROR          = "ERROR"
    const val DELETED        = "DELETED"
}

/** A timestamped transcript segment produced by the on-device ASR engine. */
@Entity(
    tableName = "transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("session_id")]
)
data class TranscriptSegmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    /** Start time in seconds from recording start. */
    @ColumnInfo(name = "start_sec") val startSec: Float,
    /** End time in seconds from recording start. */
    @ColumnInfo(name = "end_sec") val endSec: Float,
    /** Speaker label: SELF | OTHER | UNKNOWN */
    @ColumnInfo(name = "speaker") val speaker: String,
    /** Raw transcript text (stored encrypted via SQLCipher or per-row encryption). */
    @ColumnInfo(name = "text") val text: String,
    /** Confidence score [0..1] from ASR. */
    @ColumnInfo(name = "confidence") val confidence: Float = 1f,
)

/** AI-generated summary attached to a recording session. */
@Entity(
    tableName = "call_summaries",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("session_id", unique = true)]
)
data class CallSummaryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    /** Paragraph-length overview of the call/meeting. */
    @ColumnInfo(name = "overview") val overview: String,
    /** JSON array of ActionItemEntity serialised inline. */
    @ColumnInfo(name = "action_items_json") val actionItemsJson: String = "[]",
    /** JSON array of KeyDecisionEntity serialised inline. */
    @ColumnInfo(name = "key_decisions_json") val keyDecisionsJson: String = "[]",
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    /** JSON array of LinkedResource (calendar events, docs, messages). */
    @ColumnInfo(name = "linked_resources_json") val linkedResourcesJson: String = "[]",
)

/** Persisted linked resource (calendar event, document, message). */
@Entity(
    tableName = "linked_resources",
    foreignKeys = [
        ForeignKey(
            entity = CallSummaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["summary_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("summary_id")]
)
data class LinkedResourceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "summary_id") val summaryId: String,
    /** DOCUMENT | CALENDAR_EVENT | MESSAGE */
    @ColumnInfo(name = "resource_type") val resourceType: String,
    @ColumnInfo(name = "resource_id") val resourceId: String,
    @ColumnInfo(name = "display_title") val displayTitle: String,
    /** Content URI or deep-link to open the resource. */
    @ColumnInfo(name = "uri_string") val uriString: String,
    /** Similarity score [0..1] from the linking engine. */
    @ColumnInfo(name = "relevance_score") val relevanceScore: Float,
    /** Whether user confirmed / dismissed this link suggestion. */
    @ColumnInfo(name = "user_accepted") val userAccepted: Boolean? = null,
)
