package com.github.gezimos.inkos.helper.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {

    private val latestRelease = """
        {
          "tag_name": "bink-v0.2.9",
          "name": "Bink OS v0.2.9",
          "html_url": "https://github.com/MercShen/BinkOS/releases/tag/bink-v0.2.9",
          "prerelease": false,
          "assets": []
        }
    """.trimIndent()

    @Test
    fun parsesLatestReleasePayload() {
        val release = ReleaseParser.parse(latestRelease)
        assertEquals("0.2.9", release?.versionName)
        assertEquals("bink-v0.2.9", release?.tagName)
        assertEquals("https://github.com/MercShen/BinkOS/releases/tag/bink-v0.2.9", release?.pageUrl)
    }

    @Test
    fun missingTagIsRejected() {
        assertNull(ReleaseParser.parse("""{"html_url":"https://github.com/MercShen/BinkOS/releases"}"""))
    }

    @Test
    fun missingPageUrlIsRejected() {
        assertNull(ReleaseParser.parse("""{"tag_name":"bink-v0.2.9"}"""))
    }

    @Test
    fun nonJsonIsRejected() {
        assertNull(ReleaseParser.parse("<html>rate limited</html>"))
    }

    @Test
    fun versionIsExtractedFromTagShapes() {
        assertEquals("0.2.9", ReleaseParser.versionFromTag("bink-v0.2.9"))
        assertEquals("0.2.9", ReleaseParser.versionFromTag("v0.2.9"))
        assertEquals("0.2.9", ReleaseParser.versionFromTag("0.2.9"))
        assertNull(ReleaseParser.versionFromTag("nightly"))
    }

    @Test
    fun newerRemoteIsReportedAsPositive() {
        assertTrue(ReleaseParser.compare("0.2.8", "0.2.9") > 0)
        assertTrue(ReleaseParser.compare("0.2.8", "0.3.0") > 0)
        assertTrue(ReleaseParser.compare("0.9.9", "1.0.0") > 0)
    }

    @Test
    fun olderRemoteIsReportedAsNegative() {
        assertTrue(ReleaseParser.compare("0.2.9", "0.2.8") < 0)
        assertTrue(ReleaseParser.compare("1.0.0", "0.9.9") < 0)
    }

    @Test
    fun equalVersionsCompareAsZero() {
        assertEquals(0, ReleaseParser.compare("0.2.8", "0.2.8"))
        assertEquals(0, ReleaseParser.compare("0.2.8", "bink-v0.2.8"))
        assertEquals(0, ReleaseParser.compare("0.2.8", "0.2.8.0"))
    }

    @Test
    fun unparseableVersionNeverClaimsAnUpdate() {
        assertEquals(0, ReleaseParser.compare("0.2.8", "nightly"))
        assertEquals(0, ReleaseParser.compare("dev", "0.2.9"))
    }
}
