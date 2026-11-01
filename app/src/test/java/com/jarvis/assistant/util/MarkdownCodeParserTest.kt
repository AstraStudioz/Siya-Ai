package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownCodeParserTest {

    @Test
    fun testNoBlockReturnsSingleTextSegment() {
        val raw = "Hello, this is pure prose with no backticks."
        val segments = MarkdownCodeParser.parse(raw)
        assertEquals(1, segments.size)
        assertTrue(segments[0] is MessageSegment.Text)
        assertEquals(raw, (segments[0] as MessageSegment.Text).text)
    }

    @Test
    fun testSingleBlockWithLanguageTag() {
        val raw = "Intro text\n```kotlin\nval x = 10\n```\nOutro text"
        val segments = MarkdownCodeParser.parse(raw)
        assertEquals(3, segments.size)
        assertTrue(segments[0] is MessageSegment.Text)
        assertEquals("Intro text\n", (segments[0] as MessageSegment.Text).text)

        assertTrue(segments[1] is MessageSegment.Code)
        val code = segments[1] as MessageSegment.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 10", code.code)

        assertTrue(segments[2] is MessageSegment.Text)
        assertEquals("\nOutro text", (segments[2] as MessageSegment.Text).text)
    }

    @Test
    fun testNoLanguageTag() {
        val raw = "```\necho 'hello world'\n```"
        val segments = MarkdownCodeParser.parse(raw)
        assertEquals(1, segments.size)
        assertTrue(segments[0] is MessageSegment.Code)
        val code = segments[0] as MessageSegment.Code
        assertEquals("", code.language)
        assertEquals("echo 'hello world'", code.code)
    }

    @Test
    fun testUnclosedBlock() {
        val raw = "Here is the code:\n```python\ndef solve():\n    return 42"
        val segments = MarkdownCodeParser.parse(raw)
        assertEquals(2, segments.size)
        assertTrue(segments[0] is MessageSegment.Text)
        assertEquals("Here is the code:\n", (segments[0] as MessageSegment.Text).text)

        assertTrue(segments[1] is MessageSegment.Code)
        val code = segments[1] as MessageSegment.Code
        assertEquals("python", code.language)
        assertEquals("def solve():\n    return 42", code.code)
        assertTrue(code.isUnclosed)
    }

    @Test
    fun testSeveralBlocksWithTextBetween() {
        val raw = "Text before\n```java\nclass A {}\n```\nText between\n```json\n{\"key\": 1}\n```\nText after"
        val segments = MarkdownCodeParser.parse(raw)
        assertEquals(5, segments.size)

        assertTrue(segments[0] is MessageSegment.Text)
        assertTrue(segments[1] is MessageSegment.Code)
        assertEquals("java", (segments[1] as MessageSegment.Code).language)
        assertEquals("class A {}", (segments[1] as MessageSegment.Code).code)

        assertTrue(segments[2] is MessageSegment.Text)
        assertEquals("\nText between\n", (segments[2] as MessageSegment.Text).text)

        assertTrue(segments[3] is MessageSegment.Code)
        assertEquals("json", (segments[3] as MessageSegment.Code).language)
        assertEquals("{\"key\": 1}", (segments[3] as MessageSegment.Code).code)

        assertTrue(segments[4] is MessageSegment.Text)
        assertEquals("\nText after", (segments[4] as MessageSegment.Text).text)
    }
}
