package com.jarvis.assistant.util

/**
 * Single source of truth for brand identity and official links.
 * Official links: Website (https://alphabits.site/) and YouTube (https://www.youtube.com/@alphabit_s).
 */
object BrandConfig {
    const val APP_DISPLAY_NAME = "Siya Ai"
    const val CRAFTED_BY = "Alpha Bits"
    const val DEVELOPER_NAME = "Alpha Bits"
    const val OFFICIAL_WEBSITE_URL = "https://alphabits.site/"
    const val OFFICIAL_YOUTUBE_URL = "https://www.youtube.com/@alphabit_s"
    const val TELEGRAM_CHANNEL_URL = OFFICIAL_WEBSITE_URL
    const val TELEGRAM_DISCUSSION_URL = OFFICIAL_YOUTUBE_URL
    const val UNIFIED_FOOTER_TEXT = "Siya Ai • Crafted by Alpha Bits • alphabits.site"

    /** Default operator name for new installs and for missing/blank saved names. */
    const val DEFAULT_OPERATOR_NAME = "Alpha Bits"
}
