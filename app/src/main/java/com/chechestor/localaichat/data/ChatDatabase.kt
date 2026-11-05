package com.chechestor.localaichat.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Сессия (диалог), F004. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long,
    /** Сводка начала диалога для модели (F006); пользователю не показывается. */
    @ColumnInfo(defaultValue = "") val summary: String = "",
    /** id последнего сообщения, вошедшего в сводку (0 — сводки нет). */
    @ColumnInfo(name = "summary_up_to", defaultValue = "0") val summaryUpTo: Long = 0,
)

/** Сообщение диалога. role — "user" или "assistant" (системный промпт не хранится). */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("session_id")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val role: String,
    val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Размышления модели (F007): показываются свёрнутым блоком, в историю для модели не идут. */
    @ColumnInfo(defaultValue = "") val thinking: String = "",
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM sessions ORDER BY last_message_at DESC, id DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: Long): SessionEntity?

    @Query("UPDATE sessions SET summary = :summary, summary_up_to = :upTo WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String, upTo: Long)

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY id")
    fun observeMessages(sessionId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY id")
    suspend fun messages(sessionId: Long): List<MessageEntity>

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE messages SET text = :text, thinking = :thinking WHERE id = :id")
    suspend fun updateMessage(id: Long, text: String, thinking: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("UPDATE sessions SET last_message_at = :time WHERE id = :id")
    suspend fun touchSession(id: Long, time: Long)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun renameSession(id: Long, title: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)
}

@Database(entities = [SessionEntity::class, MessageEntity::class], version = 3, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        fun create(context: Context): ChatDatabase =
            Room.databaseBuilder(context, ChatDatabase::class.java, "chat.db")
                // Пилот: при смене схемы база пересоздаётся (decisions.md, 2026-11-05).
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
