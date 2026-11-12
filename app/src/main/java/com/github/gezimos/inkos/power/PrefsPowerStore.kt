package com.github.gezimos.inkos.power

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores the mode flag, the journal and the reading whitelist.
 *
 * Deliberately a separate preferences file from the launcher's main settings:
 * this is transient device state, not something the user configured, and mixing
 * it into `com.github.gezimos.inkos` would make a "reset settings" action look
 * like it should also reset the power mode.
 */
class PrefsPowerStore(context: Context) : PowerStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun loadMode(): PowerMode {
        val name = prefs.getString(KEY_MODE, null) ?: return PowerMode.NORMAL
        return PowerMode.values().firstOrNull { it.name == name } ?: PowerMode.NORMAL
    }

    override fun saveMode(mode: PowerMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    override fun loadJournal(): List<Change> {
        val raw = prefs.getString(KEY_JOURNAL, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                Change(
                    key = item.optString("key"),
                    before = item.optString("before").takeIf { it.isNotEmpty() },
                    undo = item.optString("undo").takeIf { it.isNotEmpty() }
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun saveJournal(changes: List<Change>) {
        val array = JSONArray()
        for (change in changes) {
            array.put(
                JSONObject()
                    .put("key", change.key)
                    .put("before", change.before ?: "")
                    .put("undo", change.undo ?: "")
            )
        }
        prefs.edit().putString(KEY_JOURNAL, array.toString()).apply()
    }

    /** Packages that keep working while pure-reading mode is on: reader + IME. */
    fun loadWhitelist(): Set<String> {
        val raw = prefs.getString(KEY_WHITELIST, null) ?: return DEFAULT_WHITELIST
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun saveWhitelist(packages: Set<String>) {
        prefs.edit().putString(KEY_WHITELIST, packages.joinToString("\n")).apply()
    }

    /** Whether the user has already seen the "this turns your phone off" warning. */
    fun loadWarningAccepted(): Boolean = prefs.getBoolean(KEY_WARNING, false)

    fun saveWarningAccepted() {
        prefs.edit().putBoolean(KEY_WARNING, true).apply()
    }

    private companion object {
        const val PREFS = "binkos_power"
        const val KEY_MODE = "mode"
        const val KEY_JOURNAL = "journal"
        const val KEY_WHITELIST = "whitelist"
        const val KEY_WARNING = "warning_accepted"

        /**
         * The keyboard must never be restricted: long-press actions, notes and
         * search all fail in a way that looks like a broken launcher.
         */
        val DEFAULT_WHITELIST = setOf(
            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin",
            "com.xrz.input"
        )
    }
}
