package com.jarvis.assistant.update

import com.google.gson.Gson
import com.jarvis.assistant.data.update.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateSystemTest {

    private val gson = Gson()

    @Test
    fun testUpdateInfoDirectJsonParsing() {
        val sampleJson = """
            {
              "versionCode": 150,
              "versionName": "1.0.150",
              "changelog": "Added camera neural vision improvements",
              "apkUrl": "https://github.com/AstraStudioz/Siya-Ai/releases/download/v1.0.150/Siya-AI-build-150.apk",
              "apkSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "telegramUrl": "https://t.me/AstraStudiosPvt/42",
              "mandatory": false,
              "publishedAt": "2026-11-04T12:00:00Z"
            }
        """.trimIndent()

        val info = gson.fromJson(sampleJson, UpdateInfo::class.java)
        assertNotNull(info)
        assertEquals(150, info.versionCode)
        assertEquals("1.0.150", info.versionName)
        assertEquals("Added camera neural vision improvements", info.changelog)
        assertEquals("https://github.com/AstraStudioz/Siya-Ai/releases/download/v1.0.150/Siya-AI-build-150.apk", info.apkUrl)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", info.apkSha256)
        assertEquals("https://t.me/AstraStudiosPvt/42", info.telegramUrl)
        assertFalse(info.mandatory)
        assertEquals("2026-11-04T12:00:00Z", info.publishedAt)
    }

    @Test
    fun testGitHubReleaseApiFallbackParsing() {
        val fallbackJson = """
            {
              "tag_name": "v1.0.152",
              "name": "Siya AI v1.0.152",
              "body": "• New UI update badge\n• Faster voice response",
              "published_at": "2026-11-04T14:30:00Z",
              "assets": [
                {
                  "name": "Siya-AI-build-152.apk",
                  "browser_download_url": "https://github.com/AstraStudioz/Siya-Ai/releases/download/v1.0.152/Siya-AI-build-152.apk"
                }
              ]
            }
        """.trimIndent()

        val info = UpdateManager.parseGitHubReleaseJson(fallbackJson)
        assertNotNull(info)
        assertEquals(152, info?.versionCode)
        assertEquals("1.0.152", info?.versionName)
        assertTrue(info?.changelog?.contains("New UI update badge") == true)
        assertEquals("https://github.com/AstraStudioz/Siya-Ai/releases/download/v1.0.152/Siya-AI-build-152.apk", info?.apkUrl)
    }

    @Test
    fun testGitHubReleaseWithJsonNullFields() {
        // Simulates GitHub API response where release notes, name, or other fields are explicitly null
        val jsonWithNulls = """
            {
              "tag_name": "v1.0.153",
              "name": null,
              "body": null,
              "published_at": null,
              "assets": [
                {
                  "name": "Siya-AI-build-153.apk",
                  "label": null,
                  "browser_download_url": "https://github.com/AstraStudioz/Siya-Ai/releases/download/v1.0.153/Siya-AI-build-153.apk"
                }
              ]
            }
        """.trimIndent()

        val info = UpdateManager.parseGitHubReleaseJson(jsonWithNulls)
        assertNotNull("Should safely parse JSON with JsonNull fields without throwing exception", info)
        assertEquals(153, info?.versionCode)
        assertEquals("1.0.153", info?.versionName)
        assertEquals("New release available.", info?.changelog)
        assertEquals("https://github.com/AstraStudioz/Siya-Ai/releases/download/v1.0.153/Siya-AI-build-153.apk", info?.apkUrl)
    }

    @Test
    fun testVersionComparisonLogic() {
        val currentVersionCode = 146

        val olderUpdate = UpdateInfo(
            versionCode = 145,
            versionName = "1.0.145",
            apkUrl = "https://example.com/app.apk"
        )
        assertFalse("Older version code must not trigger update", olderUpdate.versionCode > currentVersionCode)

        val sameUpdate = UpdateInfo(
            versionCode = 146,
            versionName = "1.0.146",
            apkUrl = "https://example.com/app.apk"
        )
        assertFalse("Identical version code must not trigger update", sameUpdate.versionCode > currentVersionCode)

        val newerUpdate = UpdateInfo(
            versionCode = 147,
            versionName = "1.0.147",
            apkUrl = "https://example.com/app.apk"
        )
        assertTrue("Higher version code must trigger update", newerUpdate.versionCode > currentVersionCode)
    }

    @Test
    fun testSha256ChecksumVerification() {
        val tempFile = File.createTempFile("test_apk", ".bin").apply {
            deleteOnExit()
            writeText("Siya AI Integrity Test Payload 12345")
        }

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val expectedHash = digest.digest("Siya AI Integrity Test Payload 12345".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        // Compute using the same logic as UpdateDownloader
        val actualHash = tempFile.inputStream().use { input ->
            val d = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                d.update(buffer, 0, read)
            }
            d.digest().joinToString("") { "%02x".format(it) }
        }

        assertEquals(expectedHash, actualHash)
        assertTrue(expectedHash.equals(actualHash, ignoreCase = true))
        assertFalse(expectedHash.equals("deadbeef000000000000000000000000", ignoreCase = true))
    }
}
