package com.jarvis.assistant.util

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure Kotlin utility for packaging parsed code blocks into a well-structured,
 * secure, and self-contained ZIP archive.
 */
object CodeZipBuilder {

    const val MAX_TOTAL_BYTES: Long = 10 * 1024 * 1024 // 10 MB limit
    private const val MAX_PATH_LENGTH: Int = 120
    private const val MAX_DIRECTORY_DEPTH: Int = 6
    private const val MAX_SLUG_LENGTH: Int = 30

    data class ResolvedFile(
        val path: String,
        val content: String
    )

    data class CodeZipResult(
        val zipBytes: ByteArray,
        val zipFileName: String,
        val fileNames: List<String>,
        val totalBytes: Long
    )

    /**
     * Build a ZIP from a raw markdown message containing code blocks.
     */
    fun buildZipFromMarkdown(
        rawMarkdown: String,
        title: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): CodeZipResult {
        val segments = MarkdownCodeParser.parse(rawMarkdown)
        val codeBlocks = segments.filterIsInstance<MessageSegment.Code>()
        return buildZip(codeBlocks, title, timestamp)
    }

    /**
     * Build a ZIP from a list of parsed Code blocks.
     */
    fun buildZip(
        codeBlocks: List<MessageSegment.Code>,
        title: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): CodeZipResult {
        if (codeBlocks.isEmpty()) {
            throw IllegalArgumentException("Cannot create a ZIP without any code blocks.")
        }

        val resolvedFiles = resolveFiles(codeBlocks)
        val readmeContent = generateReadme(resolvedFiles, timestamp)

        // Calculate uncompressed size
        var totalBytes = readmeContent.toByteArray(StandardCharsets.UTF_8).size.toLong()
        for (file in resolvedFiles) {
            totalBytes += file.content.toByteArray(StandardCharsets.UTF_8).size.toLong()
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw IllegalStateException("Total code size exceeds 10 MB limit.")
            }
        }

        val zipFileName = generateZipFileName(title, timestamp)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos, StandardCharsets.UTF_8).use { zos ->
            // 1. Write source files
            for (file in resolvedFiles) {
                val entry = ZipEntry(file.path).apply {
                    time = timestamp
                }
                zos.putNextEntry(entry)
                zos.write(file.content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }

            // 2. Write README.txt
            val readmeEntry = ZipEntry("README.txt").apply {
                time = timestamp
            }
            zos.putNextEntry(readmeEntry)
            zos.write(readmeContent.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }

        val fileNamesList = resolvedFiles.map { it.path } + "README.txt"
        return CodeZipResult(
            zipBytes = baos.toByteArray(),
            zipFileName = zipFileName,
            fileNames = fileNamesList,
            totalBytes = totalBytes
        )
    }

    /**
     * Resolves and deduplicates filenames for each code block following strict precedence:
     * 1. Fence info string ("language:path" or "language title=path")
     * 2. First-line comment (e.g. "// File: src/app.js", "<!-- index.html -->")
     * 3. Default based on language tag
     */
    fun resolveFiles(codeBlocks: List<MessageSegment.Code>): List<ResolvedFile> {
        val usedPaths = mutableSetOf<String>()
        val result = mutableListOf<ResolvedFile>()

        for ((index, block) in codeBlocks.withIndex()) {
            val candidate = extractFileName(block, index)
            val sanitized = sanitizePath(candidate, block.language)
            val uniquePath = deduplicatePath(sanitized, usedPaths)
            usedPaths.add(uniquePath)
            result.add(ResolvedFile(uniquePath, block.code))
        }

        return result
    }

    private fun extractFileName(block: MessageSegment.Code, index: Int): String {
        // (1) Fence info string check
        val fromInfo = parseFenceInfo(block.language)
        if (!fromInfo.isNullOrBlank()) {
            return fromInfo
        }

        // (2) First-line comment check
        val fromComment = parseFirstLineComment(block.code)
        if (!fromComment.isNullOrBlank()) {
            return fromComment
        }

        // (3) Default based on language
        return defaultFileNameForLanguage(block.language, index)
    }

    /**
     * Parses fence info string:
     * e.g. "html:index.html" -> "index.html"
     * e.g. "css:assets/style.css" -> "assets/style.css"
     * e.g. "html title=index.html" or "python title=\"main.py\"" -> "index.html" or "main.py"
     * e.g. "python path=src/main.py" -> "src/main.py"
     */
    fun parseFenceInfo(info: String): String? {
        val trimmed = info.trim()
        if (trimmed.isEmpty()) return null

        // Check for "lang:path"
        if (trimmed.contains(':')) {
            val parts = trimmed.split(':', limit = 2)
            if (parts.size == 2) {
                val candidate = parts[1].trim()
                if (candidate.isNotEmpty()) return candidate
            }
        }

        // Check for title="..." or title=... or path="..." or path=...
        val titleMatch = Regex("""(?:title|path|file)\s*=\s*["']?([^"'\s>]+)["']?""", RegexOption.IGNORE_CASE)
            .find(trimmed)
        if (titleMatch != null) {
            val candidate = titleMatch.groupValues[1].trim()
            if (candidate.isNotEmpty()) return candidate
        }

        return null
    }

    /**
     * Parses first-line comment from the code snippet:
     * e.g. "// File: src/app.js"
     * e.g. "<!-- index.html -->"
     * e.g. "# file: main.py"
     */
    fun parseFirstLineComment(code: String): String? {
        val firstLine = code.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() } ?: return null

        val blockCommentEnd = "*" + "/"
        val prefixes = listOf("//", "/" + "*", "#", "--", "<!--")
        val prefix = prefixes.firstOrNull { firstLine.startsWith(it) } ?: return null

        var stripped = firstLine.removePrefix(prefix).trim()
        if (stripped.endsWith(blockCommentEnd)) {
            stripped = stripped.removeSuffix(blockCommentEnd).trim()
        }
        if (stripped.endsWith("-->")) {
            stripped = stripped.removeSuffix("-->").trim()
        }

        // Check for "file:" or "path:"
        val lower = stripped.lowercase(Locale.ROOT)
        if (lower.startsWith("file:") || lower.startsWith("path:")) {
            stripped = stripped.substring(5).trim()
        }

        // Candidate should be a clean filename/path with an extension and no spaces
        if (stripped.contains('.') && !stripped.contains(' ') && stripped.length in 3..120) {
            return stripped
        }

        return null
    }

    /**
     * Default file names for supported languages
     */
    fun defaultFileNameForLanguage(language: String, index: Int = 0): String {
        val cleanLang = language.substringBefore(':')
            .substringBefore(' ')
            .trim()
            .lowercase(Locale.ROOT)

        return when (cleanLang) {
            "html", "htm" -> "index.html"
            "css" -> "style.css"
            "js", "javascript" -> "script.js"
            "ts", "typescript" -> "index.ts"
            "py", "python" -> "main.py"
            "kt", "kotlin" -> "Main.kt"
            "java" -> "Main.java"
            "json" -> "data.json"
            "sql" -> "query.sql"
            "sh", "bash", "shell" -> "run.sh"
            "md", "markdown" -> "README.md"
            "xml" -> "layout.xml"
            "yaml", "yml" -> "config.yaml"
            "c" -> "main.c"
            "cpp", "c++" -> "main.cpp"
            "go" -> "main.go"
            "rs", "rust" -> "main.rs"
            "php" -> "index.php"
            else -> "snippet.txt"
        }
    }

    /**
     * Normalizes and sanitizes path:
     * - Rejects or cleans ".." path traversals
     * - Removes absolute paths & backslashes
     * - Replaces control characters and forbidden chars
     * - Limits max directory depth to 6
     * - Limits max length to 120 characters
     */
    fun sanitizePath(rawPath: String, fallbackLang: String): String {
        var path = rawPath.replace('\\', '/')
            .replace(Regex("""[\x00-\x1F\x7F]"""), "")
            .trim()

        // Remove leading slashes
        while (path.startsWith("/")) {
            path = path.substring(1)
        }

        val rawSegments = path.split('/')
        val safeSegments = mutableListOf<String>()

        for (seg in rawSegments) {
            val trimmedSeg = seg.trim()
            if (trimmedSeg.isEmpty() || trimmedSeg == ".") {
                continue
            }
            if (trimmedSeg == "..") {
                // Reject traversal segment
                continue
            }

            // Sanitize segment name: keep only letters, digits, _, -, .
            val cleaned = trimmedSeg.replace(Regex("""[^a-zA-Z0-9_\-.]"""), "_")
            if (cleaned.isNotEmpty()) {
                safeSegments.add(cleaned)
            }
        }

        if (safeSegments.isEmpty()) {
            return defaultFileNameForLanguage(fallbackLang)
        }

        // Limit depth to max 6 levels
        val depthLimitedSegments = if (safeSegments.size > MAX_DIRECTORY_DEPTH) {
            safeSegments.takeLast(MAX_DIRECTORY_DEPTH)
        } else {
            safeSegments
        }

        var reconstructed = depthLimitedSegments.joinToString("/")

        // Limit total length to MAX_PATH_LENGTH while preserving extension
        if (reconstructed.length > MAX_PATH_LENGTH) {
            val fileName = depthLimitedSegments.last()
            val dirPrefix = depthLimitedSegments.dropLast(1).joinToString("/").let {
                if (it.isNotEmpty()) "$it/" else ""
            }
            val dotIdx = fileName.lastIndexOf('.')
            val baseName = if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
            val ext = if (dotIdx > 0) fileName.substring(dotIdx) else ""

            val maxBaseLen = (MAX_PATH_LENGTH - dirPrefix.length - ext.length).coerceAtLeast(1)
            val truncatedFileName = baseName.take(maxBaseLen) + ext
            reconstructed = dirPrefix + truncatedFileName
        }

        return reconstructed
    }

    /**
     * Deduplicates path collisions by inserting _2, _3, etc. before file extension.
     */
    fun deduplicatePath(path: String, usedPaths: Set<String>): String {
        if (!usedPaths.contains(path)) return path

        val lastSlash = path.lastIndexOf('/')
        val dir = if (lastSlash >= 0) path.substring(0, lastSlash + 1) else ""
        val fileName = if (lastSlash >= 0) path.substring(lastSlash + 1) else path

        val dotIdx = fileName.lastIndexOf('.')
        val baseName = if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
        val ext = if (dotIdx > 0) fileName.substring(dotIdx) else ""

        var counter = 2
        while (true) {
            val candidate = "$dir${baseName}_$counter$ext"
            if (!usedPaths.contains(candidate)) {
                return candidate
            }
            counter++
        }
    }

    /**
     * Generates standard archive filename: siya-code-<slug-30>-<yyyyMMdd-HHmm>.zip
     */
    fun generateZipFileName(title: String?, timestamp: Long): String {
        val rawTitle = title?.trim().orEmpty()
        var slug = rawTitle.lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .replace(Regex("""-+"""), "-")
            .trim('-')

        if (slug.length > MAX_SLUG_LENGTH) {
            slug = slug.take(MAX_SLUG_LENGTH).trimEnd('-')
        }
        if (slug.isEmpty()) {
            slug = "snippet"
        }

        val dateStr = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(timestamp))
        return "siya-code-$slug-$dateStr.zip"
    }

    /**
     * Generates README.txt. Does NOT contain user prompt text.
     */
    fun generateReadme(files: List<ResolvedFile>, timestamp: Long): String {
        val dateFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
        val filePaths = files.map { it.path }

        val howToRun = when {
            filePaths.any { it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true) } -> {
                "Open index.html (or the main HTML file) in any modern web browser."
            }
            filePaths.any { it.endsWith(".py", ignoreCase = true) } -> {
                "Run 'python main.py' in a terminal with Python 3 installed."
            }
            filePaths.any { it.endsWith(".js", ignoreCase = true) || it.endsWith(".ts", ignoreCase = true) } -> {
                "Run with Node.js ('node script.js' or 'node index.js') in your terminal."
            }
            filePaths.any { it.endsWith(".kt", ignoreCase = true) || it.endsWith(".java", ignoreCase = true) } -> {
                "Open in Android Studio or compile with your preferred Kotlin/Java compiler."
            }
            filePaths.any { it.endsWith(".sh", ignoreCase = true) } -> {
                "Run 'bash run.sh' in a compatible Linux/macOS shell."
            }
            else -> {
                "Refer to the included source code files for integration and execution."
            }
        }

        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine(" Siya Ai - Generated Code Archive")
        sb.appendLine(" Date: $dateFormatted")
        sb.appendLine("==================================================")
        sb.appendLine()
        sb.appendLine("Files Included:")
        for (f in filePaths) {
            sb.appendLine("  - $f")
        }
        sb.appendLine()
        sb.appendLine("How to run:")
        sb.appendLine("  $howToRun")
        sb.appendLine()
        sb.appendLine("Generated by Siya Ai Assistant.")
        return sb.toString()
    }
}
