package com.mallowlink.app.calls.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mallowlink.app.calls.db.entity.CallSummaryEntity
import com.mallowlink.app.calls.db.entity.LinkedResourceEntity
import com.mallowlink.app.calls.db.entity.RecordingSessionEntity
import com.mallowlink.app.calls.db.entity.TranscriptSegmentEntity
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// RecordingSession DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface RecordingSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: RecordingSessionEntity)

    @Update
    suspend fun update(session: RecordingSessionEntity)

    @Query("SELECT * FROM recording_sessions ORDER BY started_at DESC")
    fun observeAll(): Flow<List<RecordingSessionEntity>>

    @Query("SELECT * FROM recording_sessions WHERE id = :id")
    suspend fun getById(id: String): RecordingSessionEntity?

    @Query("SELECT * FROM recording_sessions WHERE state = :state")
    suspend fun getByState(state: String): List<RecordingSessionEntity>

    @Query("SELECT * FROM recording_sessions WHERE consent_given = 1 ORDER BY started_at DESC LIMIT 50")
    fun observeConsentedSessions(): Flow<List<RecordingSessionEntity>>

    @Query("""
        UPDATE recording_sessions SET state = :state, error_message = :error
        WHERE id = :id
    """)
    suspend fun updateState(id: String, state: String, error: String? = null)

    @Query("""
        UPDATE recording_sessions SET ended_at = :endedAt, duration_secs = :durationSecs,
        audio_path = :audioPath WHERE id = :id
    """)
    suspend fun markEnded(id: String, endedAt: Long, durationSecs: Int, audioPath: String?)

    @Query("UPDATE recording_sessions SET audio_path = NULL, state = 'DELETED' WHERE id = :id")
    suspend fun forgetAudio(id: String)

    @Query("DELETE FROM recording_sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}

// ─────────────────────────────────────────────────────────────────────────────
// Transcript DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface TranscriptSegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<TranscriptSegmentEntity>)

    @Query("SELECT * FROM transcript_segments WHERE session_id = :sessionId ORDER BY start_sec ASC")
    fun observeBySession(sessionId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE session_id = :sessionId ORDER BY start_sec ASC")
    suspend fun getBySession(sessionId: String): List<TranscriptSegmentEntity>

    @Query("SELECT * FROM transcript_segments WHERE id = :segmentId")
    suspend fun getById(segmentId: String): TranscriptSegmentEntity?

    @Query("DELETE FROM transcript_segments WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}

// ─────────────────────────────────────────────────────────────────────────────
// Summary DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface CallSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: CallSummaryEntity)

    @Query("SELECT * FROM call_summaries WHERE session_id = :sessionId")
    fun observeBySession(sessionId: String): Flow<CallSummaryEntity?>

    @Query("SELECT * FROM call_summaries WHERE session_id = :sessionId")
    suspend fun getBySession(sessionId: String): CallSummaryEntity?

    @Query("SELECT * FROM call_summaries ORDER BY generated_at DESC")
    fun observeAll(): Flow<List<CallSummaryEntity>>

    @Query("DELETE FROM call_summaries WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}

// ─────────────────────────────────────────────────────────────────────────────
// Linked Resources DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface LinkedResourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(resources: List<LinkedResourceEntity>)

    @Update
    suspend fun update(resource: LinkedResourceEntity)

    @Query("SELECT * FROM linked_resources WHERE summary_id = :summaryId ORDER BY relevance_score DESC")
    fun observeBySummary(summaryId: String): Flow<List<LinkedResourceEntity>>

    @Query("SELECT * FROM linked_resources WHERE summary_id = :summaryId ORDER BY relevance_score DESC")
    suspend fun getBySummary(summaryId: String): List<LinkedResourceEntity>

    @Query("UPDATE linked_resources SET user_accepted = :accepted WHERE id = :id")
    suspend fun setUserAccepted(id: String, accepted: Boolean)

    @Query("DELETE FROM linked_resources WHERE summary_id = :summaryId")
    suspend fun deleteBySummary(summaryId: String)
}
