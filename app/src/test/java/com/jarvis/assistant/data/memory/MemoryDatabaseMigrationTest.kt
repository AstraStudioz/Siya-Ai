package com.jarvis.assistant.data.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MemoryDatabaseMigrationTest {

    @Test
    fun databaseAnnotationExportsSchema() {
        val dbSourceFile = File("src/main/java/com/jarvis/assistant/data/memory/MemoryDatabase.kt").let {
            if (it.exists()) it else File("app/src/main/java/com/jarvis/assistant/data/memory/MemoryDatabase.kt")
        }
        assertTrue("MemoryDatabase.kt source file must exist", dbSourceFile.exists())
        val code = dbSourceFile.readText()
        assertTrue("MemoryDatabase must set exportSchema = true to prevent accidental schema divergence", code.contains("exportSchema = true"))
    }

    @Test
    fun migrationScaffoldIsConfigured() {
        assertNotNull("MemoryDatabase.MIGRATIONS scaffold array must be defined", MemoryDatabase.MIGRATIONS)
    }

    @Test
    fun destructiveMigrationOnUpgradeIsDisabled() {
        val dbSourceFile = File("src/main/java/com/jarvis/assistant/data/memory/MemoryDatabase.kt").let {
            if (it.exists()) it else File("app/src/main/java/com/jarvis/assistant/data/memory/MemoryDatabase.kt")
        }
        assertTrue("MemoryDatabase.kt source file must exist", dbSourceFile.exists())
        val code = dbSourceFile.readText()

        val regex = Regex("""\.fallbackToDestructiveMigration\(\)""")
        assertFalse("fallbackToDestructiveMigration() must be removed to protect learned memory across upgrades", regex.containsMatchIn(code))
        assertTrue("fallbackToDestructiveMigrationOnDowngrade() should be present", code.contains("fallbackToDestructiveMigrationOnDowngrade()"))
    }
}
