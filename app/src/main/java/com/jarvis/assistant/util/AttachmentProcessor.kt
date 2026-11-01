package com.jarvis.assistant.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.jarvis.assistant.data.model.ChatAttachment
import com.jarvis.assistant.network.RestInlineData
import com.jarvis.assistant.network.RestPart
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

object AttachmentProcessor {

    const val MAX_ATTACHMENTS = 5
    const val MAX_TOTAL_BYTES = 18 * 1024 * 1024L // 18 MB
    const val MAX_PDF_BYTES = 10 * 1024 * 1024L // 10 MB
    const val MAX_TEXT_BYTES = 200 * 1024L // 200 KB
    const val MAX_IMAGE_DIMENSION = 1600

    val SUPPORTED_EXTENSIONS = setOf(
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "html", "css",
        "json", "xml", "md", "csv", "txt", "sql", "sh", "c", "cpp",
        "h", "cs", "go", "rs", "php", "rb", "swift", "yaml", "yml",
        "gradle", "kts", "pdf"
    )

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }

    fun isExtensionSupported(fileName: String, mimeType: String?): Boolean {
        if (mimeType == "application/pdf" || mimeType?.startsWith("image/") == true || mimeType?.startsWith("text/") == true) {
            return true
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_EXTENSIONS
    }

    fun validateAttachment(fileName: String, mimeType: String?, fileSize: Long): ValidationResult {
        if (!isExtensionSupported(fileName, mimeType)) {
            return ValidationResult.Error("This file type is not supported yet")
        }

        val isPdf = mimeType == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true)
        val isImage = mimeType?.startsWith("image/") == true || fileName.lowercase().let {
            it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp")
        }

        if (isPdf && fileSize > MAX_PDF_BYTES) {
            return ValidationResult.Error("PDF files must be 10 MB or smaller")
        }

        if (!isPdf && !isImage && fileSize > MAX_TEXT_BYTES) {
            return ValidationResult.Error("Text and code files must be 200 KB or smaller")
        }

        return ValidationResult.Valid
    }

    fun validateTotalStaged(currentCount: Int, currentTotalBytes: Long, newFileSize: Long): ValidationResult {
        if (currentCount >= MAX_ATTACHMENTS) {
            return ValidationResult.Error("Maximum $MAX_ATTACHMENTS attachments allowed")
        }
        if (currentTotalBytes + newFileSize > MAX_TOTAL_BYTES) {
            return ValidationResult.Error("Total attachments must not exceed 18 MB")
        }
        return ValidationResult.Valid
    }

    fun getFileNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = "attachment"
        var size = 0L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex) ?: name
                    }
                    if (sizeIndex != -1) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {}

        if (size <= 0L) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    size = stream.available().toLong()
                }
            } catch (_: Exception) {}
        }

        return Pair(name, size)
    }

    fun getMediaDir(context: Context): File {
        val dir = File(context.filesDir, "chat_media")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun processImageUri(context: Context, uri: Uri): ChatAttachment? {
        val mediaDir = getMediaDir(context)
        val targetFile = File(mediaDir, "${UUID.randomUUID()}.jpg")

        return try {
            val (originalName, _) = getFileNameAndSize(context, uri)
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) return null

            val width = originalBitmap.width
            val height = originalBitmap.height
            val scaledBitmap = if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
                val ratio = width.toFloat() / height.toFloat()
                val targetW: Int
                val targetH: Int
                if (width > height) {
                    targetW = MAX_IMAGE_DIMENSION
                    targetH = (MAX_IMAGE_DIMENSION / ratio).toInt().coerceAtLeast(1)
                } else {
                    targetH = MAX_IMAGE_DIMENSION
                    targetW = (MAX_IMAGE_DIMENSION * ratio).toInt().coerceAtLeast(1)
                }
                Bitmap.createScaledBitmap(originalBitmap, targetW, targetH, true)
            } else {
                originalBitmap
            }

            FileOutputStream(targetFile).use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            ChatAttachment(
                type = "image",
                path = targetFile.absolutePath,
                name = originalName.ifBlank { targetFile.name },
                mime = "image/jpeg"
            )
        } catch (e: Exception) {
            try { targetFile.delete() } catch (_: Exception) {}
            null
        }
    }

    fun processFileUri(context: Context, uri: Uri): ChatAttachment? {
        val (name, _) = getFileNameAndSize(context, uri)
        val mime = context.contentResolver.getType(uri) ?: when {
            name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            name.endsWith(".json", ignoreCase = true) -> "application/json"
            name.endsWith(".xml", ignoreCase = true) -> "application/xml"
            name.endsWith(".html", ignoreCase = true) -> "text/html"
            else -> "text/plain"
        }

        val isImage = mime.startsWith("image/")
        if (isImage) {
            return processImageUri(context, uri)
        }

        val mediaDir = getMediaDir(context)
        val ext = name.substringAfterLast('.', "")
        val safeExt = if (ext.isNotEmpty()) ".$ext" else ""
        val targetFile = File(mediaDir, "${UUID.randomUUID()}$safeExt")

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            ChatAttachment(
                type = if (mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)) "file" else "file",
                path = targetFile.absolutePath,
                name = name,
                mime = mime
            )
        } catch (e: Exception) {
            try { targetFile.delete() } catch (_: Exception) {}
            null
        }
    }

    fun attachmentToRestPart(attachment: ChatAttachment): RestPart? {
        val file = File(attachment.path)
        if (!file.exists() || !file.canRead()) return null

        return try {
            val isPdf = attachment.mime == "application/pdf" || attachment.name.endsWith(".pdf", ignoreCase = true)
            val isImage = attachment.type == "image" || attachment.mime.startsWith("image/")

            if (isImage || isPdf) {
                val bytes = file.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val resolvedMime = if (isPdf) "application/pdf" else attachment.mime
                RestPart(inlineData = RestInlineData(mimeType = resolvedMime, data = base64))
            } else {
                val textContent = file.readText(Charsets.UTF_8)
                RestPart(text = "File: ${attachment.name}\n\n$textContent")
            }
        } catch (e: Exception) {
            null
        }
    }
}
