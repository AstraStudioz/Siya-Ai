package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentValidationTest {

    @Test
    fun testSupportedAndUnsupportedTypes() {
        assertTrue(AttachmentProcessor.isExtensionSupported("MainActivity.kt", null))
        assertTrue(AttachmentProcessor.isExtensionSupported("script.py", null))
        assertTrue(AttachmentProcessor.isExtensionSupported("document.pdf", "application/pdf"))
        assertTrue(AttachmentProcessor.isExtensionSupported("image.jpg", "image/jpeg"))
        assertTrue(AttachmentProcessor.isExtensionSupported("data.json", "application/json"))

        // Unsupported types
        assertTrue(!AttachmentProcessor.isExtensionSupported("report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertTrue(!AttachmentProcessor.isExtensionSupported("sheet.xlsx", null))
        assertTrue(!AttachmentProcessor.isExtensionSupported("archive.zip", null))
        assertTrue(!AttachmentProcessor.isExtensionSupported("program.exe", null))

        val docxResult = AttachmentProcessor.validateAttachment("report.docx", null, 5000)
        assertTrue(docxResult is AttachmentProcessor.ValidationResult.Error)
        assertEquals("This file type is not supported yet", (docxResult as AttachmentProcessor.ValidationResult.Error).message)
    }

    @Test
    fun testPdfSizeLimits() {
        val validPdfBytes = 9 * 1024 * 1024L // 9 MB
        val validResult = AttachmentProcessor.validateAttachment("sample.pdf", "application/pdf", validPdfBytes)
        assertTrue(validResult is AttachmentProcessor.ValidationResult.Valid)

        val oversizePdfBytes = 11 * 1024 * 1024L // 11 MB
        val oversizeResult = AttachmentProcessor.validateAttachment("sample.pdf", "application/pdf", oversizePdfBytes)
        assertTrue(oversizeResult is AttachmentProcessor.ValidationResult.Error)
        assertTrue((oversizeResult as AttachmentProcessor.ValidationResult.Error).message.contains("10 MB"))
    }

    @Test
    fun testTextAndCodeSizeLimits() {
        val validCodeBytes = 150 * 1024L // 150 KB
        val validResult = AttachmentProcessor.validateAttachment("Code.kt", null, validCodeBytes)
        assertTrue(validResult is AttachmentProcessor.ValidationResult.Valid)

        val oversizeCodeBytes = 250 * 1024L // 250 KB
        val oversizeResult = AttachmentProcessor.validateAttachment("Code.kt", null, oversizeCodeBytes)
        assertTrue(oversizeResult is AttachmentProcessor.ValidationResult.Error)
        assertTrue((oversizeResult as AttachmentProcessor.ValidationResult.Error).message.contains("200 KB"))
    }

    @Test
    fun testCountAndTotalSizeValidation() {
        // Valid 3 attachments with 3 MB total
        val res1 = AttachmentProcessor.validateTotalStaged(3, 3 * 1024 * 1024L, 1 * 1024 * 1024L)
        assertTrue(res1 is AttachmentProcessor.ValidationResult.Valid)

        // Reaching 5 attachments is ok
        val res2 = AttachmentProcessor.validateTotalStaged(4, 3 * 1024 * 1024L, 1 * 1024 * 1024L)
        assertTrue(res2 is AttachmentProcessor.ValidationResult.Valid)

        // Exceeding 5 attachments
        val resExceedCount = AttachmentProcessor.validateTotalStaged(5, 3 * 1024 * 1024L, 1 * 1024 * 1024L)
        assertTrue(resExceedCount is AttachmentProcessor.ValidationResult.Error)
        assertTrue((resExceedCount as AttachmentProcessor.ValidationResult.Error).message.contains("Maximum 5"))

        // Exceeding 18 MB
        val resExceedBytes = AttachmentProcessor.validateTotalStaged(2, 17 * 1024 * 1024L, 2 * 1024 * 1024L)
        assertTrue(resExceedBytes is AttachmentProcessor.ValidationResult.Error)
        assertTrue((resExceedBytes as AttachmentProcessor.ValidationResult.Error).message.contains("18 MB"))
    }
}
