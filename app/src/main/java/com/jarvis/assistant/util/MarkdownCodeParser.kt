package com.jarvis.assistant.util

sealed class MessageSegment {
    data class Text(val text: String) : MessageSegment()
    data class Code(
        val language: String,
        val code: String,
        val isUnclosed: Boolean = false
    ) : MessageSegment()
}

object MarkdownCodeParser {

    fun parse(raw: String): List<MessageSegment> {
        if (raw.isEmpty()) return emptyList()

        val segments = mutableListOf<MessageSegment>()
        var cursor = 0
        val length = raw.length

        while (cursor < length) {
            val startBackticks = raw.indexOf("```", cursor)
            if (startBackticks == -1) {
                val remainingText = raw.substring(cursor)
                if (remainingText.isNotEmpty()) {
                    segments.add(MessageSegment.Text(remainingText))
                }
                break
            }

            if (startBackticks > cursor) {
                val leadingText = raw.substring(cursor, startBackticks)
                if (leadingText.isNotEmpty()) {
                    segments.add(MessageSegment.Text(leadingText))
                }
            }

            val afterOpenTicks = startBackticks + 3
            val newlineIndex = raw.indexOf('\n', afterOpenTicks)
            val closingBackticks = raw.indexOf("```", afterOpenTicks)

            if (closingBackticks == -1) {
                // Unclosed trailing block (currently generating)
                if (newlineIndex != -1) {
                    val lang = raw.substring(afterOpenTicks, newlineIndex).trim()
                    val code = raw.substring(newlineIndex + 1).trimEnd('\r', '\n')
                    segments.add(MessageSegment.Code(lang, code, isUnclosed = true))
                } else {
                    val content = raw.substring(afterOpenTicks).trimEnd('\r', '\n')
                    segments.add(MessageSegment.Code("", content, isUnclosed = true))
                }
                break
            } else {
                if (newlineIndex != -1 && newlineIndex < closingBackticks) {
                    val lang = raw.substring(afterOpenTicks, newlineIndex).trim()
                    val code = raw.substring(newlineIndex + 1, closingBackticks).trimEnd('\r', '\n')
                    segments.add(MessageSegment.Code(lang, code, isUnclosed = false))
                } else {
                    val content = raw.substring(afterOpenTicks, closingBackticks).trimEnd('\r', '\n')
                    segments.add(MessageSegment.Code("", content, isUnclosed = false))
                }
                cursor = closingBackticks + 3
            }
        }

        return segments
    }
}
