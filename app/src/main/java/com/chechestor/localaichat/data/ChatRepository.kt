package com.chechestor.localaichat.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** Хранение диалогов (F004). */
class ChatRepository(private val db: ChatDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = db.chatDao()

    fun sessions(): Flow<List<SessionEntity>> = dao.observeSessions()
    fun session(id: Long): Flow<SessionEntity?> = dao.observeSession(id)
    fun messages(sessionId: Long): Flow<List<MessageEntity>> = dao.observeMessages(sessionId)
    suspend fun messageList(sessionId: Long): List<MessageEntity> = dao.messages(sessionId)

    /** Создаёт сессию с первым сообщением пользователя (пустые диалоги не сохраняются). */
    suspend fun createSession(firstUserText: String): Long = db.withTransaction {
        val t = now()
        dao.insertSession(SessionEntity(title = autoTitle(firstUserText), createdAt = t, lastMessageAt = t))
    }

    suspend fun addMessage(sessionId: Long, role: String, text: String): Long = db.withTransaction {
        val t = now()
        val id = dao.insertMessage(MessageEntity(sessionId = sessionId, role = role, text = text, createdAt = t))
        dao.touchSession(sessionId, t)
        id
    }

    suspend fun updateMessageText(id: Long, text: String) = dao.updateMessageText(id, text)

    suspend fun deleteMessage(id: Long) = dao.deleteMessage(id)

    /** Пустое название не сохраняется. */
    suspend fun rename(sessionId: Long, title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty()) return false
        dao.renameSession(sessionId, t.take(MAX_TITLE))
        return true
    }

    suspend fun delete(sessionId: Long) = dao.deleteSession(sessionId)

    companion object {
        const val AUTO_TITLE_LENGTH = 40
        private const val MAX_TITLE = 200

        /** Автоназвание: первые ~40 символов первого сообщения, с многоточием, если обрезано. */
        fun autoTitle(text: String): String {
            val oneLine = text.trim().replace(Regex("\\s+"), " ")
            if (oneLine.length <= AUTO_TITLE_LENGTH) return oneLine
            val cut = oneLine.take(AUTO_TITLE_LENGTH)
            // Обрезаем по границе слова, если она не слишком близко к началу.
            val atWord = if (oneLine[AUTO_TITLE_LENGTH] == ' ') cut
            else cut.substringBeforeLast(' ', cut).takeIf { it.length >= AUTO_TITLE_LENGTH / 2 } ?: cut
            return atWord.trimEnd() + "…"
        }
    }
}
