package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

class CodeZipBuilderTest {

    @Test
    fun testFenceInfoStringParsing() {
        val blocks = listOf(
            MessageSegment.Code(
                language = "html:index.html",
                code = "<!DOCTYPE html><html><body><h1>Hello</h1></body></html>"
            ),
            MessageSegment.Code(
                language = "css:assets/style.css",
                code = "body { margin: 0; background: #000; }"
            ),
            MessageSegment.Code(
                language = "python title=\"src/app.py\"",
                code = "print('Hello world')"
            )
        )

        val files = CodeZipBuilder.resolveFiles(blocks)
        assertEquals(3, files.size)
        assertEquals("index.html", files[0].path)
        assertEquals("assets/style.css", files[1].path)
        assertEquals("src/app.py", files[2].path)
    }

    @Test
    fun testFirstLineCommentParsing() {
        val blocks = listOf(
            MessageSegment.Code(
                language = "javascript",
                code = "// File: js/calculator.js\nfunction add(a, b) { return a + b; }"
            ),
            MessageSegment.Code(
                language = "html",
                code = "<!-- template.html -->\n<div>Siya Ai</div>"
            ),
            MessageSegment.Code(
                language = "python",
                code = "# file: scripts/process.py\nimport sys\nprint(sys.argv)"
            )
        )

        val files = CodeZipBuilder.resolveFiles(blocks)
        assertEquals(3, files.size)
        assertEquals("js/calculator.js", files[0].path)
        assertEquals("template.html", files[1].path)
        assertEquals("scripts/process.py", files[2].path)
    }

    @Test
    fun testDefaultLanguageFallbackAndDeduplication() {
        val blocks = listOf(
            MessageSegment.Code(language = "html", code = "<h1>1</h1>"),
            MessageSegment.Code(language = "html", code = "<h1>2</h1>"),
            MessageSegment.Code(language = "python", code = "print(1)"),
            MessageSegment.Code(language = "unknown_lang", code = "some code")
        )

        val files = CodeZipBuilder.resolveFiles(blocks)
        assertEquals(4, files.size)
        assertEquals("index.html", files[0].path)
        assertEquals("index_2.html", files[1].path)
        assertEquals("main.py", files[2].path)
        assertEquals("snippet.txt", files[3].path)
    }

    @Test
    fun testPathSafetyAndNormalization() {
        // Traversal rejection
        val badPath = "../../etc/passwd"
        val sanitized = CodeZipBuilder.sanitizePath(badPath, "text")
        assertFalse(sanitized.contains(".."))

        // Backslash and absolute path
        val winPath = """\folder\subfolder\index.html"""
        val sanitizedWin = CodeZipBuilder.sanitizePath(winPath, "html")
        assertEquals("folder/subfolder/index.html", sanitizedWin)

        // Depth limit
        val deepPath = "a/b/c/d/e/f/g/h/file.txt"
        val sanitizedDeep = CodeZipBuilder.sanitizePath(deepPath, "text")
        val segments = sanitizedDeep.split('/')
        assertTrue(segments.size <= 6)
    }

    @Test
    fun testBuildZipIntegrityAndReadme() {
        val blocks = listOf(
            MessageSegment.Code(
                language = "html:index.html",
                code = "<h1>Siya Code</h1>"
            ),
            MessageSegment.Code(
                language = "js:app.js",
                code = "console.log('running');"
            )
        )

        val zipResult = CodeZipBuilder.buildZip(blocks, title = "My Super Web App 🚀")
        assertTrue(zipResult.zipFileName.startsWith("siya-code-my-super-web-app-"))
        assertTrue(zipResult.zipFileName.endsWith(".zip"))
        assertTrue(zipResult.fileNames.contains("index.html"))
        assertTrue(zipResult.fileNames.contains("app.js"))
        assertTrue(zipResult.fileNames.contains("README.txt"))

        // Inspect zip content using ZipInputStream
        val readEntries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zipResult.zipBytes), StandardCharsets.UTF_8).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val content = zis.readBytes().toString(StandardCharsets.UTF_8)
                readEntries[entry.name] = content
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        assertEquals(3, readEntries.size)
        assertEquals("<h1>Siya Code</h1>", readEntries["index.html"])
        assertEquals("console.log('running');", readEntries["app.js"])
        assertNotNull(readEntries["README.txt"])
        val readme = readEntries["README.txt"]!!
        assertTrue(readme.contains("Siya Ai - Generated Code Archive"))
        assertTrue(readme.contains("Open index.html"))
        assertFalse(readme.contains("My Super Web App")) // No user prompt in README
    }

    @Test(expected = IllegalStateException::class)
    fun testSizeLimitExceededThrows() {
        // Fake 11MB block
        val hugeCode = "A".repeat(11 * 1024 * 1024)
        val blocks = listOf(
            MessageSegment.Code(language = "txt", code = hugeCode)
        )
        CodeZipBuilder.buildZip(blocks, title = "huge")
    }
}
