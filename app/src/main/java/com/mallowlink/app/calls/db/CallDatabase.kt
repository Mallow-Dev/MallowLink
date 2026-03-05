package com.mallowlink.app.calls.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mallowlink.app.calls.db.dao.CallSummaryDao
import com.mallowlink.app.calls.db.dao.LinkedResourceDao
import com.mallowlink.app.calls.db.dao.RecordingSessionDao
import com.mallowlink.app.calls.db.dao.TranscriptSegmentDao
import com.mallowlink.app.calls.db.entity.CallSummaryEntity
import com.mallowlink.app.calls.db.entity.LinkedResourceEntity
import com.mallowlink.app.calls.db.entity.RecordingSessionEntity
import com.mallowlink.app.calls.db.entity.TranscriptSegmentEntity

// Separate database so it can be fully wiped by the user independently
// of the main document-index database.
@Database(
    entities = [
        RecordingSessionEntity::class,
        TranscriptSegmentEntity::class,
        CallSummaryEntity::class,
        LinkedResourceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class CallDatabase : RoomDatabase() {
    abstract fun sessionDao(): RecordingSessionDao
    abstract fun transcriptDao(): TranscriptSegmentDao
    abstract fun summaryDao(): CallSummaryDao
    abstract fun linkedResourceDao(): LinkedResourceDao
}
