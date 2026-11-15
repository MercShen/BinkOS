package com.github.gezimos.inkos.helper

import com.github.gezimos.inkos.helper.BackupJsonValidator.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreferenceSchemaTest {
    @Test
    fun staticSchemaCoversKnownPreferenceTypes() {
        assertTrue(BackupPreferenceSchema.statics.size > 150)
        assertEquals(ValueType.INTEGER, BackupPreferenceSchema.typeFor("ANDROID_WIDGET_ID"))
        assertEquals(ValueType.FLOAT, BackupPreferenceSchema.typeFor("SHORT_SWIPE_THRESHOLD_RATIO"))
        assertEquals(ValueType.LONG, BackupPreferenceSchema.typeFor("EVENTS_CALENDAR_ID"))
        assertEquals(ValueType.SET, BackupPreferenceSchema.typeFor("HIDDEN_APPS"))
        assertNull(BackupPreferenceSchema.typeFor("totally_unknown_key"))
        assertNull(BackupPreferenceSchema.typeFor("_sync_"))
        val keyPattern = Regex("^[A-Za-z0-9_]+$")
        for (key in BackupPreferenceSchema.statics.keys) {
            assertTrue("invalid generated schema key: $key", keyPattern.matches(key))
        }
    }

    @Test
    fun dynamicRulesCoverAliasAndIndexedAppKeys() {
        assertEquals(ValueType.STRING, BackupPreferenceSchema.typeFor("app_alias_com.example"))
        assertEquals(ValueType.STRING, BackupPreferenceSchema.typeFor("APP_ALIAS_com.example"))
        assertEquals(ValueType.STRING, BackupPreferenceSchema.typeFor("APP_NAME_1_2"))
        assertEquals(ValueType.STRING, BackupPreferenceSchema.typeFor("APP_PACKAGE_1"))
        assertEquals(ValueType.STRING, BackupPreferenceSchema.typeFor("APP_ACTIVITY_1"))
        assertEquals(ValueType.STRING, BackupPreferenceSchema.typeFor("APP_SHORTCUT_ID_1"))
        assertEquals(ValueType.STRING, BackupPreferenceSchema.typeFor("APP_USER_1"))
        assertNull(BackupPreferenceSchema.typeFor("APP_NAME"))
    }
}
