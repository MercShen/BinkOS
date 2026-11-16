package com.github.gezimos.inkos.helper

import com.github.gezimos.inkos.helper.BackupJsonValidator.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonValidatorTest {
    @Test
    fun acceptsEnvelopeAndLegacyLayouts() {
        assertNull(BackupJsonValidator.validate("{\"type\":\"inkos_full\",\"version\":1,\"prefs\":{\"a\":\"b\"}}"))
        assertNull(BackupJsonValidator.validate("{\"a\":\"b\",\"n\":\"1\",\"flag\":\"x\"}"))
    }

    @Test
    fun rejectsInvalidJsonAndNonObjectRoot() {
        assertEquals("Backup file is empty", BackupJsonValidator.validate("   "))
        assertEquals("Backup file is not valid JSON", BackupJsonValidator.validate("{"))
        assertEquals("Backup file must contain a JSON object", BackupJsonValidator.validate("[]"))
    }

    @Test
    fun rejectsWrongEnvelopeShape() {
        assertNotNull(BackupJsonValidator.validate("{\"type\":\"inkos_theme\",\"prefs\":{\"a\":\"x\"}}"))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":[]}"))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{}}"))
        assertNotNull(BackupJsonValidator.validate("{\"type\":1,\"prefs\":{\"a\":\"x\"}}"))
    }

    @Test
    fun versionMustBeNumericAndWithinSupportedRange() {
        assertNull(BackupJsonValidator.validate("{\"type\":\"inkos_full\",\"version\":1,\"prefs\":{\"a\":\"b\"}}"))
        assertNotNull(BackupJsonValidator.validate("{\"type\":\"inkos_full\",\"version\":\"1\",\"prefs\":{\"a\":\"b\"}}"))
        val future = BackupJsonValidator.validate("{\"type\":\"inkos_full\",\"version\":2,\"prefs\":{\"a\":\"b\"}}")
        assertNotNull(future)
        assertTrue(future!!.contains("newer version"))
        assertNotNull(BackupJsonValidator.validate("{\"type\":\"inkos_full\",\"version\":1.5,\"prefs\":{\"a\":\"b\"}}"))
    }

    @Test
    fun knownPreferenceTypesMustMatch() {
        val expected = mapOf(
            "flag" to ValueType.BOOLEAN,
            "count" to ValueType.INTEGER,
            "ratio" to ValueType.FLOAT,
            "stamp" to ValueType.LONG,
            "name" to ValueType.STRING,
            "pkg_set" to ValueType.SET
        )
        assertNull(
            BackupJsonValidator.validate(
                "{\"prefs\":{\"flag\":true,\"count\":3,\"ratio\":1.5,\"stamp\":99,\"name\":\"x\",\"pkg_set\":[\"a\",\"b\"]}}",
                expected
            )
        )
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"count\":\"3\"}}", expected))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"count\":1.5}}", expected))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"ratio\":\"1.5\"}}", expected))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"stamp\":1.5}}", expected))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"flag\":\"true\"}}", expected))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"name\":1}}", expected))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"pkg_set\":[1,2]}}", expected))
    }

    @Test
    fun integerAndLongBoundsAreEnforced() {
        val expected = mapOf("i" to ValueType.INTEGER, "l" to ValueType.LONG)
        assertNull(BackupJsonValidator.validate("{\"prefs\":{\"i\":2147483647,\"l\":9223372036854775807}}", expected))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"i\":2147483648}}", expected))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"i\":-2147483649}}", expected))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"l\":1e300}}", expected))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"l\":9223372036854775808}}", expected))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"l\":9007199254740992.5}}", expected))
    }

    @Test
    fun schemaProtectsKnownKeysOnFreshInstall() {
        // Known keys are strict even when they are not stored on this device yet.
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"ANDROID_WIDGET_ID\":\"abc\"}}", schema = BackupPreferenceSchema))
        assertNull(BackupJsonValidator.validate("{\"prefs\":{\"ANDROID_WIDGET_ID\":5}}", schema = BackupPreferenceSchema))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"SHORT_SWIPE_THRESHOLD_RATIO\":\"1.5\"}}", schema = BackupPreferenceSchema))
        assertNull(BackupJsonValidator.validate("{\"prefs\":{\"SHORT_SWIPE_THRESHOLD_RATIO\":1.5}}", schema = BackupPreferenceSchema))
    }

    @Test
    fun absentKeysAcceptPrimitivesSoFreshInstallCanRestore() {
        // Keys in neither schema nor sharedPrefs: primitives are accepted for legacy backups.
        assertNull(BackupJsonValidator.validate("{\"prefs\":{\"unknown_name\":\"text\"}}"))
        assertNull(BackupJsonValidator.validate("{\"prefs\":{\"unknown_number\":5}}"))
        assertNull(BackupJsonValidator.validate("{\"prefs\":{\"unknown_bool\":true}}"))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"unknown_name\":{\"nested\":1}}}"))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"unknown_array\":[1,2]}}"))
        assertNull(BackupJsonValidator.validate("{\"prefs\":{\"old_string_set\":[\"a\",\"b\"]}}", schema = BackupPreferenceSchema))
        assertNull(BackupJsonValidator.validate("{\"prefs\":{\"custom_font_paths\":[\"/a\",\"/b\"]}}"))
        assertNull(BackupJsonValidator.validate("{\"prefs\":{\"APP_ALIAS_com.x\":\"Alias\"}}"))
    }

    @Test
    fun rejectsTooManySettingsAndBlankKeys() {
        val values = (0..2000).joinToString(",") { "\"k$it\":\"v\"" }
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{$values}}"))
        assertNotNull(BackupJsonValidator.validate("{\"prefs\":{\"\":\"blank key\"}}"))
    }

    @Test
    fun themeEnvelopeIsAccepted() {
        val valid = "{\"type\":\"inkos_theme\",\"version\":1,\"prefs\":{\"BACKGROUND_COLOR\":-1,\"LAUNCHER_FONT\":\"PublicSans\"}}"
        assertNull(BackupJsonValidator.validateTheme(valid, 1))
    }

    @Test
    fun themeRequiresTypeAndSupportedVersion() {
        assertNotNull(BackupJsonValidator.validateTheme("{\"version\":1,\"prefs\":{\"BACKGROUND_COLOR\":-1}}", 1))
        assertNotNull(BackupJsonValidator.validateTheme("{\"type\":\"inkos_full\",\"version\":1,\"prefs\":{\"BACKGROUND_COLOR\":-1}}", 1))
        assertNotNull(BackupJsonValidator.validateTheme("{\"type\":\"inkos_theme\",\"prefs\":{\"BACKGROUND_COLOR\":-1}}", 1))
        assertNotNull(BackupJsonValidator.validateTheme("{\"type\":\"inkos_theme\",\"version\":\"1\",\"prefs\":{\"BACKGROUND_COLOR\":-1}}", 1))
        assertNotNull(BackupJsonValidator.validateTheme("{\"type\":\"inkos_theme\",\"version\":1.5,\"prefs\":{\"BACKGROUND_COLOR\":-1}}", 1))
        assertNotNull(BackupJsonValidator.validateTheme("{\"type\":\"inkos_theme\",\"version\":0,\"prefs\":{\"BACKGROUND_COLOR\":-1}}", 1))
        val future = BackupJsonValidator.validateTheme("{\"type\":\"inkos_theme\",\"version\":2,\"prefs\":{\"BACKGROUND_COLOR\":-1}}", 1)
        assertNotNull(future)
        assertTrue(future!!.contains("newer version"))
    }

    @Test
    fun themeRejectsInvalidShapeAndValueTypes() {
        assertNotNull(BackupJsonValidator.validateTheme("   ", 1))
        assertNotNull(BackupJsonValidator.validateTheme("{", 1))
        assertNotNull(BackupJsonValidator.validateTheme("[]", 1))
        assertNotNull(BackupJsonValidator.validateTheme("{\"type\":\"inkos_theme\",\"version\":1,\"prefs\":[]}", 1))
        assertNotNull(BackupJsonValidator.validateTheme("{\"type\":\"inkos_theme\",\"version\":1,\"prefs\":{}}", 1))
        // Known numeric key must stay numeric.
        assertNotNull(BackupJsonValidator.validateTheme("{\"type\":\"inkos_theme\",\"version\":1,\"prefs\":{\"BACKGROUND_COLOR\":\"x\"}}", 1))
        // Unknown keys are ignored on import, primitives are fine.
        assertNull(BackupJsonValidator.validateTheme("{\"type\":\"inkos_theme\",\"version\":1,\"prefs\":{\"future_key\":\"x\"}}", 1))
    }
}
