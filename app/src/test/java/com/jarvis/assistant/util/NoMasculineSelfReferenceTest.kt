package com.jarvis.assistant.util

import com.jarvis.assistant.data.model.GeminiConstants
import org.junit.Assert.fail
import org.junit.Test
import java.util.regex.Pattern

class NoMasculineSelfReferenceTest {

    private val masculineRegex = Pattern.compile(
        """(?i)(karta|raha|rha|dekhta|sunta|bolta|jaata|aata|bhejta|dhundta|dhundhta|janta|chahta|samajhta)\s+(hu|hun|hoon)\b|\b(karunga|dunga|lunga|bataunga|dikhaunga|bhejunga|chalaunga|kholunga|rukunga|sunaunga|dhundhunga)\b"""
    )

    @Test
    fun testAllPersonasHaveNoMasculineSelfReferences() {
        val testPersonas = GeminiConstants.SUPPORTED_PERSONALITIES + listOf(GeminiConstants.PERSONALITY_BOYFRIEND)
        val testLanguages = listOf(GeminiConstants.DEFAULT_LANGUAGE, "Hindi", "Hinglish")

        for (persona in testPersonas) {
            for (lang in testLanguages) {
                val prompt = PromptGenerator.generateSystemPrompt(
                    personality = persona,
                    userName = "TestUser",
                    language = lang
                )

                val lines = prompt.lines()
                var inVoiceIdentityHeader = false

                for ((index, line) in lines.withIndex()) {
                    if (line.contains("[VOICE & GENDER IDENTITY - HIGHEST PRIORITY]")) {
                        inVoiceIdentityHeader = true
                        continue
                    }
                    if (inVoiceIdentityHeader) {
                        if (line.startsWith("[") || line.startsWith("You are Siya Ai")) {
                            inVoiceIdentityHeader = false
                        } else {
                            // Skip identity block explanation and wrong/right examples
                            continue
                        }
                    }

                    if (line.contains("Wrong:")) {
                        continue
                    }

                    val matcher = masculineRegex.matcher(line)
                    if (matcher.find()) {
                        val matchedWord = matcher.group()
                        fail(
                            "Found masculine self-reference '$matchedWord' for persona '$persona' and language '$lang' at line ${index + 1}:\n$line"
                        )
                    }
                }
            }
        }
    }
}
