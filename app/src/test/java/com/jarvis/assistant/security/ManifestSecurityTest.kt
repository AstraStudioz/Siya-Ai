package com.jarvis.assistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class ManifestSecurityTest {

    @Test
    fun activitiesWithoutIntentFiltersAreNotExported() {
        val manifestFile = File("src/main/AndroidManifest.xml").let {
            if (it.exists()) it else File("app/src/main/AndroidManifest.xml")
        }
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(manifestFile)
        val activityNodes = doc.getElementsByTagName("activity")

        var verifiedCount = 0
        for (i in 0 until activityNodes.length) {
            val activity = activityNodes.item(i) as Element
            val name = activity.getAttribute("android:name")
            val exported = activity.getAttribute("android:exported")
            val intentFilters = activity.getElementsByTagName("intent-filter")

            if (intentFilters.length == 0) {
                assertEquals(
                    "Activity $name does not have intent-filter and must have android:exported=\"false\"",
                    "false",
                    exported
                )
                verifiedCount++
            } else {
                // Activities with intent-filters (e.g. MainActivity launcher)
                assertEquals(
                    "Launcher activity $name with intent-filter must explicitly declare android:exported",
                    "true",
                    exported
                )
            }
        }

        assertTrue("Must have verified at least 5 internal activities without intent filters", verifiedCount >= 5)
    }
}
