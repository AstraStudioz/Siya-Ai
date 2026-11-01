package com.jarvis.assistant.appwidget

import java.util.Calendar
import kotlin.math.abs

/**
 * Provides curated, original daily motivational quotes from Siya AI.
 * Completely local, no network required, with day-of-year rotation.
 */
object WidgetQuoteHelper {

    val QUOTES = listOf(
        "Small steps create big dreams.",
        "Your potential is endless. Go make it happen.",
        "Take a deep breath. You are doing wonderfully.",
        "Believe in yourself as much as I believe in you.",
        "Every day is a fresh canvas for your story.",
        "Radiate kindness and watch the world glow.",
        "Focus on progress, never perfection.",
        "You have the courage to overcome anything today.",
        "One kind thought can transform your entire morning.",
        "Keep moving forward, one beautiful step at a time.",
        "Your sparkle lights up more worlds than you know.",
        "Embrace today with an open heart and gentle mind.",
        "Great things take time. Be patient with your journey.",
        "You are stronger than any doubt in your mind.",
        "Celebrate how far you have already come.",
        "Let your curiosity lead the way today.",
        "A peaceful mind is your greatest superpower.",
        "Smile — today holds something truly wonderful for you.",
        "Confidence is quiet; belief is strong.",
        "You are capable of crafting extraordinary moments.",
        "Listen to your heart and trust your inner strength.",
        "Every challenge is a stepping stone to growth.",
        "Be proud of the effort you put in every single day.",
        "Stay curious, stay inspired, and never stop dreaming.",
        "Peace begins the moment you choose joy.",
        "You bring warmth and light wherever you go.",
        "Take gentle care of yourself along the way.",
        "Today is a gift. Unfold it with courage.",
        "Your unique voice matters in this world.",
        "Kindness to yourself is where all beauty starts.",
        "Dream boldly and take steady action.",
        "Remember that you are never truly alone.",
        "Magic happens when you don't give up.",
        "Keep your hopes high and your worries low.",
        "I'm cheering for you today and every day."
    )

    /**
     * Selects an original quote deterministically based on day of year.
     */
    fun getQuoteForDay(dayOfYear: Int): String {
        val index = abs(dayOfYear) % QUOTES.size
        return QUOTES[index]
    }

    /**
     * Gets today's quote based on current device calendar.
     */
    fun getTodayQuote(): String {
        val dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        return getQuoteForDay(dayOfYear)
    }
}
