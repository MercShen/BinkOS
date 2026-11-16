package com.github.gezimos.inkos.helper.utils

import java.net.URLEncoder

/**
 * Builds the prefilled GitHub issue URL opened from the crash dialog.
 *
 * Crash reports go to this fork's tracker, not to upstream inkOS: the dialog used to
 * hand the report to an upstream mail address, which both routed BinkOS bugs away from
 * the project and sent user logs to a third party.
 *
 * Privacy: only the stack trace section of the saved crash log is included. The
 * "Recent User Actions" section stays on the device because it lists apps the user opened.
 */
object CrashIssueLink {

    const val ISSUE_URL_BASE = "https://github.com/MercShen/BinkOS/issues/new"

    private const val STACK_TRACE_MARKER = "=== Crash Stack Trace ==="
    private const val MAX_STACK_CHARS = 1200

    fun build(
        appName: String,
        versionName: String,
        versionCode: Long,
        androidRelease: String,
        sdkInt: Int,
        manufacturer: String,
        model: String,
        crashLog: String?
    ): String {
        val title = "Crash: $appName $versionName"
        val body = buildString {
            appendLine("### What happened")
            appendLine("<!-- Please describe what you were doing when it crashed. -->")
            appendLine()
            appendLine("### Device")
            appendLine("- App: $appName $versionName ($versionCode)")
            appendLine("- Android: $androidRelease (API $sdkInt)")
            appendLine("- Device: ${manufacturer.trim()} ${model.trim()}".trim())
            val stack = stackTraceSnippet(crashLog)
            if (!stack.isNullOrBlank()) {
                appendLine()
                appendLine("### Stack trace")
                appendLine("```")
                appendLine(stack)
                appendLine("```")
            }
        }
        return "$ISSUE_URL_BASE?title=${encode(title)}&body=${encode(body)}"
    }

    /**
     * Returns the stack trace part of a saved crash log, or null when there is nothing to send.
     * A log without the marker is treated as raw stack trace; either way the result is capped.
     */
    fun stackTraceSnippet(crashLog: String?): String? {
        if (crashLog.isNullOrBlank()) return null
        val markerIndex = crashLog.indexOf(STACK_TRACE_MARKER)
        val raw = if (markerIndex >= 0) {
            crashLog.substring(markerIndex + STACK_TRACE_MARKER.length)
        } else {
            crashLog
        }.trim()
        if (raw.isEmpty()) return null

        val capped = if (raw.length > MAX_STACK_CHARS) raw.substring(0, MAX_STACK_CHARS) else raw
        if (capped.length == raw.length) return capped

        // Cut at a line boundary so the pasted trace does not end mid-frame.
        val lastNewline = capped.lastIndexOf('\n')
        val trimmed = if (lastNewline > 0) capped.substring(0, lastNewline) else capped
        return trimmed.trimEnd() + "\n… (truncated)"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
