package com.mallowlink.app.data.repository

import com.mallowlink.app.data.db.dao.ChatDao
import com.mallowlink.app.data.db.entity.ChatMessageEntity
import com.mallowlink.app.data.db.entity.ChatSessionEntity
import com.mallowlink.app.domain.model.ChatMessage
import com.mallowlink.app.domain.model.ChatSession
import com.mallowlink.app.domain.model.Citation
import com.mallowlink.app.domain.model.FilterType
import com.mallowlink.app.domain.model.MessageRole
import com.mallowlink.app.domain.model.SourceFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val json: Json,
) {

    fun observeSessions(): Flow<List<ChatSession>> =
        chatDao.observeSessions().map { list -> list.map { it.toDomain() } }

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        chatDao.observeMessages(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun createSession(title: String, filter: SourceFilter = SourceFilter.ALL): ChatSession {
        val now = System.currentTimeMillis()
        val entity = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            filterType = filter.type,
            filterValue = filter.value,
        )
        chatDao.upsertSession(entity)
        return entity.toDomain()
    }

    suspend fun saveMessage(
        sessionId: String,
        role: MessageRole,
        content: String,
        citations: List<Citation> = emptyList(),
        error: String? = null,
    ): ChatMessage {
        val entity = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role,
            content = content,
            citationsJson = json.encodeToString(citations),
            timestamp = System.currentTimeMillis(),
            error = error,
        )
        chatDao.upsertMessage(entity)
        chatDao.updateSessionMeta(
            sessionId,
            entity.timestamp,
            content.take(60).ifBlank { "Chat" }
        )
        return entity.toDomain()
    }

    suspend fun deleteSession(sessionId: String) {
        val entity = chatDao.getSession(sessionId) ?: return
        chatDao.deleteSession(entity)
    }

    private fun ChatSessionEntity.toDomain() = ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        activeSourceFilter = SourceFilter(filterType, filterValue),
    )

    private fun ChatMessageEntity.toDomain() = ChatMessage(
        id = id,
        sessionId = sessionId,
        role = role,
        content = content,
        citations = runCatching { json.decodeFromString<List<Citation>>(citationsJson) }.getOrDefault(emptyList()),
        timestamp = timestamp,
        error = error,
    )
}
