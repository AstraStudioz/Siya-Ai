package com.jarvis.assistant.util

/**
 * Single source of truth for brand identity and community links.
 * Change branding or community destinations here instead of editing individual screens.
 */
object BrandConfig {
    const val APP_DISPLAY_NAME = "Siya Ai"
    const val DEVELOPER_NAME = "ShivuOfc"
    const val COPYRIGHT_HOLDER = DEVELOPER_NAME

    /** Default operator name for new installs and for missing/blank saved names. */
    const val DEFAULT_OPERATOR_NAME = DEVELOPER_NAME

    /** Official updates channel; also the destination of the developer attribution footer. */
    const val TELEGRAM_CHANNEL_URL = "https://t.me/CodingWithShivu"

    /** Community discussion group. */
    const val TELEGRAM_DISCUSSION_URL = "https://t.me/ShivuxDiscussion"
}
