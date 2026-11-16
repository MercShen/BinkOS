package com.github.gezimos.inkos.helper.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashIssueLinkTest {

    private val log = """
        Crash Report - Mon Nov 16 10:00:00 GMT+08:00 2026
        Thread: main

        === Device Info ===
        App Version: 0.2.6 (9)
        Installed From: com.android.vending
        Device: Bigme HiBreak Pro
        Android Version: 14 (API 34)

        === Recent User Actions ===
        09:59:58 - com.example.secretapp App Launched

        === Crash Stack Trace ===
        java.lang.IllegalStateException: boom
            at com.github.gezimos.inkos.MainActivity.onCreate(MainActivity.kt:42)
            at android.app.Activity.performCreate(Activity.java:8000)
    """.trimIndent()

    private fun build(crashLog: String? = log) = CrashIssueLink.build(
        appName = "Bink OS",
        versionName = "0.2.6",
        versionCode = 9L,
        androidRelease = "14",
        sdkInt = 34,
        manufacturer = "Bigme",
        model = "HiBreak Pro",
        crashLog = crashLog
    )

    @Test
    fun targetsBinkOSTracker() {
        val url = build()
        assertTrue(url.startsWith(CrashIssueLink.ISSUE_URL_BASE + "?title="))
        assertFalse("must not point at the upstream inkOS repository", url.contains("gezimos/inkOS"))
    }

    @Test
    fun titleAndBodyArePrefilled() {
        val url = build()
        assertTrue(url.contains("title=Crash%3A%20Bink%20OS%200.2.6"))
        assertTrue(url.contains("Bink%20OS%200.2.6%20%289%29"))
        assertTrue(url.contains("Android%3A%2014%20%28API%2034%29"))
        assertTrue(url.contains("Device%3A%20Bigme%20HiBreak%20Pro"))
        assertTrue(url.contains("java.lang.IllegalStateException%3A%20boom"))
    }

    @Test
    fun omitsRecentUserActions() {
        val url = build()
        assertFalse("user action log can reveal which apps were opened", url.contains("secretapp"))
        assertFalse(url.contains("Recent%20User%20Actions"))
    }

    @Test
    fun capsLongStackTraceAtLineBoundary() {
        val huge = "=== Crash Stack Trace ===\n" +
            (1..400).joinToString("\n") { "    at com.example.Frame$it(Frame.kt:$it)" }
        val snippet = CrashIssueLink.stackTraceSnippet(huge)!!
        assertTrue(snippet.length <= 1200 + "\n… (truncated)".length)
        assertTrue(snippet.endsWith("… (truncated)"))
        assertFalse(snippet.contains("Frame400"))
    }

    @Test
    fun missingLogStillProducesUrl() {
        val url = build(crashLog = null)
        assertTrue(url.startsWith(CrashIssueLink.ISSUE_URL_BASE))
        assertFalse(url.contains("Stack%20trace"))
        assertNull(CrashIssueLink.stackTraceSnippet("   "))
    }
}
