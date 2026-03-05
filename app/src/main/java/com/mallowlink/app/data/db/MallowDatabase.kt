package com.mallowlink.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.mallowlink.app.data.db.dao.ChatDao
import com.mallowlink.app.data.db.dao.ChunkDao
import com.mallowlink.app.data.db.dao.DocumentDao
import com.mallowlink.app.data.db.dao.SourceDao
import com.mallowlink.app.data.db.entity.ChatMessageEntity
import com.mallowlink.app.data.db.entity.ChatSessionEntity
import com.mallowlink.app.data.db.entity.DocumentChunkEntity
import com.mallowlink.app.data.db.entity.IndexedDocumentEntity
import com.mallowlink.app.data.db.entity.IndexedSourceEntity
import com.mallowlink.app.domain.model.FileType
import com.mallowlink.app.domain.model.FilterType
import com.mallowlink.app.domain.model.MessageRole
import com.mallowlink.app.domain.model.SourceType

@Database(
    entities = [
        IndexedSourceEntity::class,
        IndexedDocumentEntity::class,
        DocumentChunkEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(MallowTypeConverters::class)
abstract class MallowDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun documentDao(): DocumentDao
    abstract fun chunkDao(): ChunkDao
    abstract fun chatDao(): ChatDao
}

class MallowTypeConverters {
    @TypeConverter fun sourceTypeToString(v: SourceType): String = v.name
    @TypeConverter fun stringToSourceType(v: String): SourceType = SourceType.valueOf(v)

    @TypeConverter fun fileTypeToString(v: FileType): String = v.name
    @TypeConverter fun stringToFileType(v: String): FileType = FileType.valueOf(v)

    @TypeConverter fun filterTypeToString(v: FilterType): String = v.name
    @TypeConverter fun stringToFilterType(v: String): FilterType = FilterType.valueOf(v)

    @TypeConverter fun messageRoleToString(v: MessageRole): String = v.name
    @TypeConverter fun stringToMessageRole(v: String): MessageRole = MessageRole.valueOf(v)
}
