package com.nexvio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionTest {
    @Test fun initialVersionIsOne() {
        assertEquals(1, 1)
    }

    @Test fun findsNewerMainBuildAndItsApk() {
        val releases = """
            [
              {
                "draft": false,
                "tag_name": "main-build-667",
                "name": "Nexvio main build",
                "body": "Playback diagnostics and fixes",
                "assets": [
                  {
                    "name": "Nexvio.apk",
                    "browser_download_url": "https://github.com/nexarion666-png/Nexvio-/releases/download/main-build-667/Nexvio.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val update = parseLatestNexvioRelease(releases, "1.0.481")

        assertEquals("1.0.667", update?.first)
        assertEquals("Playback diagnostics and fixes", update?.second)
        assertEquals(
            "https://github.com/nexarion666-png/Nexvio-/releases/download/main-build-667/Nexvio.apk",
            update?.third
        )
    }

    @Test fun doesNotOfferCurrentOrOlderBuild() {
        val releases = """
            [{
              "draft": false,
              "tag_name": "main-build-667",
              "assets": [{
                "name": "Nexvio.apk",
                "browser_download_url": "https://github.com/nexarion666-png/Nexvio-/Nexvio.apk"
              }]
            }]
        """.trimIndent()

        assertNull(parseLatestNexvioRelease(releases, "1.0.667"))
    }

    @Test fun ignoresReleaseWithoutNexvioApk() {
        val releases = """
            [{
              "draft": false,
              "tag_name": "main-build-668",
              "assets": [{
                "name": "source.zip",
                "browser_download_url": "https://example.com/source.zip"
              }]
            }]
        """.trimIndent()

        assertNull(parseLatestNexvioRelease(releases, "1.0.667"))
    }
}
