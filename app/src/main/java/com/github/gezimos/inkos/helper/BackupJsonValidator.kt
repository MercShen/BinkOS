package com.github.gezimos.inkos.helper

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure validation of a full-backup JSON payload (v1 envelope or legacy root map).
 *
 * Value types are checked against [expectedTypes] (the device's current values) plus the static
 * [BackupPreferenceSchema] generated from Prefs typed getters, so a string cannot overwrite a known
 * integer/float/long/boolean/set preference even on a fresh install. Keys in neither source are
 * legacy/unknown: primitive values and bounded string sets are accepted; objects/mixed arrays are not.
 *
 * Kept Android-free so JVM tests cover invalid JSON/shape/type/limit rules.
 */
object BackupJsonValidator {
    enum class ValueType { BOOLEAN, INTEGER, FLOAT, LONG, STRING, SET, NUMBER }

    private const val MAX_KEYS = 2_000
    private const val MAX_KEY_LENGTH = 256
    private const val MAX_LIST_VALUES = 100_000

    fun validate(
        json: String,
        expectedTypes: Map<String, ValueType> = emptyMap(),
        schema: BackupPreferenceSchema? = null,
        maxVersion: Int = 1
    ): String? {
        if (json.isBlank()) return "Backup file is empty"
        val rootElement = try {
            JsonParser().parse(json)
        } catch (_: Exception) {
            return "Backup file is not valid JSON"
        }
        if (!rootElement.isJsonObject) return "Backup file must contain a JSON object"
        val root = rootElement.asJsonObject

        val settings: JsonObject = if (root.has("prefs")) {
            val type = root.get("type")
            if (type != null && type.isJsonPrimitive && type.asJsonPrimitive.isString) {
                if (type.asJsonPrimitive.asString != "inkos_full") return "Backup file is not a full settings backup"
            } else if (type != null) {
                return "Backup type field is invalid"
            }
            val version = root.get("version")
            if (version != null) {
                if (!version.isJsonPrimitive || !version.asJsonPrimitive.isNumber) {
                    return "Backup version field must be numeric"
                }
                val number = version.asJsonPrimitive.asDouble
                if (number % 1.0 != 0.0 || number < 0 || number > maxVersion) {
                    return "Backup version is not supported; update Bink OS to a newer version"
                }
            }
            val prefs = root.get("prefs")
            if (prefs == null || !prefs.isJsonObject) return "Backup settings field is invalid"
            prefs.asJsonObject
        } else {
            // Legacy layout: the root itself is the settings map.
            root
        }

        if (settings.size() == 0) return "Backup contains no settings"
        if (settings.size() > MAX_KEYS) return "Backup contains too many settings"
        for ((key, value) in settings.entrySet()) {
            if (key.isBlank()) return "Backup contains an empty setting name"
            if (key.length > MAX_KEY_LENGTH) return "Backup setting name is too long"
            if (value == null || value.isJsonNull) continue
            val expected = expectedTypes[key] ?: schema?.typeFor(key)
            val error = checkValue(key, value, expected)
            if (error != null) return error
        }
        return null
    }

    /**
     * Validates an appearance-only theme payload (`{"type":"inkos_theme","version":1,"prefs":{...}}`).
     * Theme keys are a hand-maintained whitelist, so values are additionally checked against
     * [BackupPreferenceSchema] to stop a malformed JSON from writing e.g. a string where an
     * integer color/size lives. Unknown keys are accepted as primitives and ignored on import.
     */
    fun validateTheme(json: String, maxVersion: Int = 1): String? {
        if (json.isBlank()) return "Theme file is empty"
        val rootElement = try {
            JsonParser().parse(json)
        } catch (_: Exception) {
            return "Theme file is not valid JSON"
        }
        if (!rootElement.isJsonObject) return "Theme file must contain a JSON object"
        val root = rootElement.asJsonObject

        val type = root.get("type")
        if (type == null || !type.isJsonPrimitive || !type.asJsonPrimitive.isString ||
            type.asJsonPrimitive.asString != "inkos_theme"
        ) {
            return "Theme file is not a Bink OS theme"
        }
        val version = root.get("version") ?: return "Theme version field is missing"
        if (!version.isJsonPrimitive || !version.asJsonPrimitive.isNumber) {
            return "Theme version field must be numeric"
        }
        val number = version.asJsonPrimitive.asDouble
        if (number % 1.0 != 0.0 || number < 1 || number > maxVersion) {
            return "Theme version is not supported; update Bink OS to a newer version"
        }
        val prefs = root.get("prefs")
        if (prefs == null || !prefs.isJsonObject) return "Theme settings field is invalid"
        val settings = prefs.asJsonObject
        if (settings.size() == 0) return "Theme contains no settings"
        if (settings.size() > MAX_KEYS) return "Theme contains too many settings"
        for ((key, value) in settings.entrySet()) {
            if (key.isBlank()) return "Theme contains an empty setting name"
            if (key.length > MAX_KEY_LENGTH) return "Theme setting name is too long"
            if (value == null || value.isJsonNull) continue
            val error = checkValue(key, value, BackupPreferenceSchema.typeFor(key))
            if (error != null) return error
        }
        return null
    }

    private fun checkValue(
        key: String,
        value: com.google.gson.JsonElement,
        expected: ValueType?
    ): String? = when (expected) {
        ValueType.BOOLEAN -> if (isBoolean(value)) null else "Backup setting '$key' must be true/false"
        ValueType.INTEGER -> validateInteger(value, key)
        ValueType.FLOAT -> validateFloat(value, key)
        ValueType.LONG -> validateLong(value, key)
        ValueType.NUMBER -> validateFiniteNumber(value, key)
        ValueType.STRING -> if (isString(value)) null else "Backup setting '$key' must be a string"
        ValueType.SET -> validateStringSet(value, key)
        null -> when {
            isLegacyMultiValueKey(key) || value.isJsonArray -> validateStringSet(value, key)
            // Unknown/legacy key: primitives are accepted for old backups.
            isString(value) || isBoolean(value) || isNumber(value) -> null
            else -> "Backup setting '$key' has an unsupported value"
        }
    }

    private fun isBoolean(value: com.google.gson.JsonElement): Boolean =
        value.isJsonPrimitive && value.asJsonPrimitive.isBoolean

    private fun isNumber(value: com.google.gson.JsonElement): Boolean =
        value.isJsonPrimitive && value.asJsonPrimitive.isNumber

    private fun isString(value: com.google.gson.JsonElement): Boolean =
        value.isJsonPrimitive && value.asJsonPrimitive.isString

    private fun validateFiniteNumber(value: com.google.gson.JsonElement, key: String): String? {
        if (!isNumber(value)) return "Backup setting '$key' must be a number"
        val d = value.asJsonPrimitive.asDouble
        if (!d.isFinite()) return "Backup setting '$key' must be a finite number"
        return null
    }

    private fun validateInteger(value: com.google.gson.JsonElement, key: String): String? {
        validateFiniteNumber(value, key)?.let { return it }
        val d = value.asJsonPrimitive.asDouble
        if (d % 1.0 != 0.0) return "Backup setting '$key' must be an integer"
        if (d < Int.MIN_VALUE.toDouble() || d > Int.MAX_VALUE.toDouble()) {
            return "Backup setting '$key' is out of range"
        }
        return null
    }

    private fun validateLong(value: com.google.gson.JsonElement, key: String): String? {
        validateFiniteNumber(value, key)?.let { return it }
        return try {
            value.asJsonPrimitive.asBigDecimal.longValueExact()
            null
        } catch (_: ArithmeticException) {
            "Backup setting '$key' must be a whole number in Long range"
        }
    }

    private fun validateFloat(value: com.google.gson.JsonElement, key: String): String? {
        validateFiniteNumber(value, key)?.let { return it }
        val d = value.asJsonPrimitive.asDouble
        if (d < -Float.MAX_VALUE || d > Float.MAX_VALUE) return "Backup setting '$key' is out of range"
        return null
    }

    /** SET entries historically accepted either a string or an array of strings. */
    private fun validateStringSet(value: com.google.gson.JsonElement, key: String): String? {
        if (isString(value)) return null
        if (!value.isJsonArray) return "Backup setting '$key' must be a string or string list"
        val array = value.asJsonArray
        if (array.size() > MAX_LIST_VALUES) return "Backup setting '$key' has too many values"
        array.forEach { item ->
            if (item == null || !isString(item)) return "Backup setting '$key' has an unsupported list value"
        }
        return null
    }

    private fun isLegacyMultiValueKey(key: String): Boolean =
        key == "custom_font_paths" || key == "APP_ALIAS" || key.startsWith("APP_ALIAS_")
}
