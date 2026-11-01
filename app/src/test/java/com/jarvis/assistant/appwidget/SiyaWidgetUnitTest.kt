package com.jarvis.assistant.appwidget
 
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
 
class SiyaWidgetUnitTest {
 
    @Test
    fun widgetTimeHelperReturnsCorrectGreetingsForAllHours() {
        // Morning: 05 - 11
        for (hour in 5..11) {
            assertEquals("Hour $hour should be Good Morning!", "Good Morning!", WidgetTimeHelper.getGreeting(hour))
        }
 
        // Afternoon: 12 - 16
        for (hour in 12..16) {
            assertEquals("Hour $hour should be Good Afternoon!", "Good Afternoon!", WidgetTimeHelper.getGreeting(hour))
        }
 
        // Evening: 17 - 20
        for (hour in 17..20) {
            assertEquals("Hour $hour should be Good Evening!", "Good Evening!", WidgetTimeHelper.getGreeting(hour))
        }
 
        // Night: 21 - 23 and 00 - 04
        val nightHours = listOf(0, 1, 2, 3, 4, 21, 22, 23)
        for (hour in nightHours) {
            assertEquals("Hour $hour should be Good Night!", "Good Night!", WidgetTimeHelper.getGreeting(hour))
        }
    }
 
    @Test
    fun widgetQuoteHelperHasAtLeastThirtyQuotesAndRotatesDeterministically() {
        assertTrue("Quote list must contain at least 30 quotes", WidgetQuoteHelper.QUOTES.size >= 30)
 
        // Same day returns identical quote
        val quoteDay100A = WidgetQuoteHelper.getQuoteForDay(100)
        val quoteDay100B = WidgetQuoteHelper.getQuoteForDay(100)
        assertNotNull(quoteDay100A)
        assertEquals(quoteDay100A, quoteDay100B)
 
        // Consecutive days return distinct quotes
        val quoteDay10 = WidgetQuoteHelper.getQuoteForDay(10)
        val quoteDay11 = WidgetQuoteHelper.getQuoteForDay(11)
        assertNotEquals(quoteDay10, quoteDay11)
 
        // Boundary days (e.g. end of year) wrap around safely
        val quoteDay365 = WidgetQuoteHelper.getQuoteForDay(365)
        val quoteDay366 = WidgetQuoteHelper.getQuoteForDay(366)
        assertTrue(quoteDay365.isNotBlank())
        assertTrue(quoteDay366.isNotBlank())
    }
 
    @Test
    fun widgetCacheStalenessLogicHandlesFreshAndStaleTimestamps() {
        val now = 1700000000000L
        val twoHoursAgo = now - (2 * 60 * 60 * 1000L)
        val threeHoursAndOneSecondAgo = now - (WidgetCache.MAX_CACHE_AGE_MS + 1000L)
 
        // Uninitialized timestamp (0) is stale
        assertTrue("Timestamp 0 must be considered stale", WidgetCache.isCacheStale(0L, now))
 
        // Negative timestamp is stale
        assertTrue("Negative timestamp must be considered stale", WidgetCache.isCacheStale(-100L, now))
 
        // Fresh timestamps (< 3 hours) are NOT stale
        assertFalse("Current time must not be stale", WidgetCache.isCacheStale(now, now))
        assertFalse("2 hours ago must not be stale", WidgetCache.isCacheStale(twoHoursAgo, now))
 
        // Timestamps exceeding 3 hours are stale
        assertTrue("3 hours and 1s ago must be stale", WidgetCache.isCacheStale(threeHoursAndOneSecondAgo, now))
    }
}
