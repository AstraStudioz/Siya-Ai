package com.jarvis.assistant.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionTest {

    @Test
    fun testCleanAnsiRemovesColorAndControlCodes() {
        val coloredText = "\u001B[31mError:\u001B[0m \u001B[1mFile not found\u001B[m"
        val cleaned = TerminalSession.cleanAnsi(coloredText)
        assertEquals("Error: File not found", cleaned)
    }

    @Test
    fun testCommandResultCombinedOutput() {
        val both = CommandResult(0, stdout = "hello\n", stderr = "warning\n")
        assertEquals("hello\nwarning", both.combinedOutput)

        val outOnly = CommandResult(0, stdout = "success\n", stderr = "")
        assertEquals("success", outOnly.combinedOutput)

        val errOnly = CommandResult(1, stdout = "", stderr = "fatal error\n")
        assertEquals("fatal error", errOnly.combinedOutput)

        val empty = CommandResult(0, stdout = "", stderr = "")
        assertEquals("", empty.combinedOutput)
    }

    @Test
    fun testCommandResultSuccessProperty() {
        val ok = CommandResult(0, "ok", "", isTimedOut = false)
        assertTrue(ok.success)

        val failed = CommandResult(1, "", "error", isTimedOut = false)
        assertFalse(failed.success)

        val timedOut = CommandResult(0, "", "timed out", isTimedOut = true)
        assertFalse(timedOut.success)
    }
}
