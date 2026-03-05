package com.mallowlink.app.di

import android.content.Context
import androidx.room.Room
import com.mallowlink.app.calls.asr.TranscriptionEngine
import com.mallowlink.app.calls.db.CallDatabase
import com.mallowlink.app.calls.db.dao.CallSummaryDao
import com.mallowlink.app.calls.db.dao.LinkedResourceDao
import com.mallowlink.app.calls.db.dao.RecordingSessionDao
import com.mallowlink.app.calls.db.dao.TranscriptSegmentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// CallsModule – provides all calls-feature singletons
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object CallsModule {

    @Provides
    @Singleton
    fun provideCallDatabase(@ApplicationContext context: Context): CallDatabase =
        Room.databaseBuilder(context, CallDatabase::class.java, "mallowlink_calls.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideSessionDao(db: CallDatabase): RecordingSessionDao = db.sessionDao()
    @Provides fun provideTranscriptDao(db: CallDatabase): TranscriptSegmentDao = db.transcriptDao()
    @Provides fun provideSummaryDao(db: CallDatabase): CallSummaryDao = db.summaryDao()
    @Provides fun provideLinkedResourceDao(db: CallDatabase): LinkedResourceDao = db.linkedResourceDao()

    @Provides
    @Singleton
    fun provideTranscriptionEngine(@ApplicationContext context: Context): TranscriptionEngine =
        TranscriptionEngine(context)
}
