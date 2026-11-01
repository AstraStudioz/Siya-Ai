package com.jarvis.assistant.data.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatSessionDao

    companion object {
        private const val DATABASE_NAME = "siya_chat_history.db"

        val MIGRATIONS: Array<Migration> = arrayOf(
            // Schema migrations registered here to protect chat history across upgrades
        )

        @Volatile
        private var instance: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*MIGRATIONS)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
