package com.jarvis.assistant.chat

import androidx.room.Database
import com.jarvis.assistant.data.chat.ChatDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatDatabaseMigrationTest {

    @Test
    fun databaseAnnotationExportsSchema() {
        val dbSourceFile = File("src/main/java/com/jarvis/assistant/data/chat/ChatDatabase.kt").let {
            if (it.exists()) it else File("app/src/main/java/com/jarvis/assistant/data/chat/ChatDatabase.kt")
        }
        assertTrue("ChatDatabase.kt source file must exist", dbSourceFile.exists())
        val code = dbSourceFile.readText()
        assertTrue("ChatDatabase must set exportSchema = true to prevent accidental schema divergence", code.contains("exportSchema = true"))

        val schemaFile = File("schemas/com.jarvis.assistant.data.chat.ChatDatabase/1.json").let {
            if (it.exists()) it else File("app/schemas/com.jarvis.assistant.data.chat.ChatDatabase/1.json")
        }
        assertTrue("Room exported schema JSON file must exist", schemaFile.exists())
    }

    @Test
    fun migrationScaffoldIsConfigured() {
        assertNotNull("ChatDatabase.MIGRATIONS scaffold array must be defined", ChatDatabase.MIGRATIONS)
    }

    @Test
    fun destructiveMigrationOnUpgradeIsDisabled() {
        val dbSourceFile = File("src/main/java/com/jarvis/assistant/data/chat/ChatDatabase.kt").let {
            if (it.exists()) it else File("app/src/main/java/com/jarvis/assistant/data/chat/ChatDatabase.kt")
        }
        assertTrue("ChatDatabase.kt source file must exist", dbSourceFile.exists())
        val code = dbSourceFile.readText()

        // Verify fallbackToDestructiveMigration() is not called on databaseBuilder
        // (only fallbackToDestructiveMigrationOnDowngrade is acceptable)
        val regex = Regex("""\.fallbackToDestructiveMigration\(\)""")
        assertFalse("fallbackToDestructiveMigration() must be removed to protect user chats across upgrades", regex.containsMatchIn(code))
        assertTrue("fallbackToDestructiveMigrationOnDowngrade() should be present", code.contains("fallbackToDestructiveMigrationOnDowngrade()"))
    }
}
