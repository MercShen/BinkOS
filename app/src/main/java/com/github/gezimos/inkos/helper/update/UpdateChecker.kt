package com.github.gezimos.inkos.helper.update

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub Releases whether a newer Bink OS exists.
 *
 * Deliberately small: one GET, short timeouts, no retries. A failed check is a normal outcome
 * here (the device may be offline or GitHub may be unreachable), and the settings row turns it
 * into "tap to retry" plus the release page, so there is nothing to queue or resume.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    const val LATEST_RELEASE_API = "https://api.github.com/repos/MercShen/BinkOS/releases/latest"

    /** Fallback the UI opens when the check fails or a release has no usable page URL. */
    const val RELEASES_PAGE = "https://github.com/MercShen/BinkOS/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    fun check(
        currentVersionName: String,
        userAgent: String,
        apiUrl: String = LATEST_RELEASE_API,
    ): UpdateResult {
        val body = try {
            fetchBody(apiUrl, userAgent)
        } catch (e: Exception) {
            Log.d(TAG, "check failed: ${e.javaClass.simpleName} ${e.message}")
            return UpdateResult.Failed(FailureReason.NETWORK)
        } ?: return UpdateResult.Failed(FailureReason.BAD_RESPONSE)

        val release = ReleaseParser.parse(body) ?: run {
            Log.d(TAG, "release payload could not be parsed")
            return UpdateResult.Failed(FailureReason.BAD_RESPONSE)
        }

        return if (ReleaseParser.compare(currentVersionName, release.versionName) > 0) {
            Log.d(TAG, "update available: local=$currentVersionName remote=${release.versionName}")
            UpdateResult.Available(release)
        } else {
            Log.d(TAG, "up to date: local=$currentVersionName remote=${release.versionName}")
            UpdateResult.UpToDate
        }
    }

    private fun fetchBody(url: String, userAgent: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            // GitHub rejects requests without a User-Agent.
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", userAgent)

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "unexpected HTTP status $status")
                return null
            }

            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
