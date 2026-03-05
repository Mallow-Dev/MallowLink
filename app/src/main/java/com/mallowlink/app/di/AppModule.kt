package com.mallowlink.app.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.mallowlink.app.data.db.MallowDatabase
import com.mallowlink.app.data.db.dao.ChatDao
import com.mallowlink.app.data.db.dao.ChunkDao
import com.mallowlink.app.data.db.dao.DocumentDao
import com.mallowlink.app.data.db.dao.SourceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// AppModule – provides app-scoped singletons
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    @Provides
    @Singleton
    fun provideMallowDatabase(@ApplicationContext context: Context): MallowDatabase =
        Room.databaseBuilder(context, MallowDatabase::class.java, "mallowlink.db")
            .fallbackToDestructiveMigration()  // dev-mode; add proper migrations for release
            .build()

    @Provides fun provideSourceDao(db: MallowDatabase): SourceDao = db.sourceDao()
    @Provides fun provideDocumentDao(db: MallowDatabase): DocumentDao = db.documentDao()
    @Provides fun provideChunkDao(db: MallowDatabase): ChunkDao = db.chunkDao()
    @Provides fun provideChatDao(db: MallowDatabase): ChatDao = db.chatDao()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
