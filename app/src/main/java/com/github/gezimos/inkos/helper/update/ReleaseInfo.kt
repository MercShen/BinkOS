package com.github.gezimos.inkos.helper.update

import com.google.gson.JsonParser

/**
 * A published Bink OS release, reduced to what the in-app update check needs.
 *
 * The app never downloads or installs anything itself: it only compares versions and
 * hands the release page to the browser. Keep this model free of Android types so the
 * parsing and comparison rules stay unit-testable on the JVM.
 */
data class ReleaseInfo(
    val versionName: String,
    val tagName: String,
    val pageUrl: String,
)

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val release: ReleaseInfo) : UpdateResult
    data class Failed(val reason: FailureReason) : UpdateResult
}

enum class FailureReason {
    NETWORK,
    BAD_RESPONSE,
}

object ReleaseParser {

    // Matches "0.2.9", "v0.2.9" and "bink-v0.2.9"; the version is the first group.
    private val versionPattern = Regex("""v?(\d+(?:\.\d+){1,3})""")

    /**
     * Parses the JSON body of GitHub's `releases/latest` endpoint.
     *
     * Returns null rather than throwing so a changed or unexpected payload can only ever
     * turn into a "could not check" message, never into a wrong "update available" claim.
     */
    fun parse(json: String): ReleaseInfo? {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null

        val tag = root.get("tag_name")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val pageUrl = root.get("html_url")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val versionName = versionFromTag(tag) ?: return null

        return ReleaseInfo(versionName = versionName, tagName = tag, pageUrl = pageUrl)
    }

    /** Extracts the numeric version from a release tag, e.g. `bink-v0.2.9` -> `0.2.9`. */
    fun versionFromTag(tag: String): String? =
        versionPattern.find(tag.trim())?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /**
     * Compares two version names component by component.
     *
     * Returns a positive number when [remote] is newer than [local], a negative number when it
     * is older, and 0 when they are equal or cannot be compared. Unparseable input deliberately
     * counts as "equal" so a malformed tag can never produce a false update prompt.
     */
    fun compare(local: String, remote: String): Int {
        val left = components(versionFromTag(local) ?: local) ?: return 0
        val right = components(versionFromTag(remote) ?: remote) ?: return 0

        for (index in 0 until maxOf(left.size, right.size)) {
            val localPart = left.getOrElse(index) { 0 }
            val remotePart = right.getOrElse(index) { 0 }
            if (localPart != remotePart) return remotePart.compareTo(localPart)
        }
        return 0
    }

    private fun components(version: String): List<Int>? {
        val trimmed = version.trim()
        if (trimmed.isEmpty()) return null

        val parts = trimmed.split('.')
        if (parts.size > 4) return null

        return parts.map { part -> part.toIntOrNull() ?: return null }
    }
}
