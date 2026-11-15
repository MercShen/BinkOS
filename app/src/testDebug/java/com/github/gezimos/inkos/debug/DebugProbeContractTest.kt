package com.github.gezimos.inkos.debug

import org.junit.Assert.*
import org.junit.Test

class DebugProbeContractTest {
    @Test fun debugReadProbesHaveNoWriteGate() {
        for (op in listOf("params", "gestures", "checkwall")) assertNull(DebugProbeContract.rejection(mapOf(op to true)))
    }
    @Test fun debugWritesAreGuardedBeforeAnyOperation() {
        for ((op, value) in listOf("alias" to "pkg", "dump" to true, "zipbackup" to true,
            "font" to "font.ttf", "wallpaper" to "wall.png", "file" to "prefs.json")) {
            assertNotNull(DebugProbeContract.rejection(mapOf(op to value)))
            assertNull(DebugProbeContract.rejection(mapOf(op to value, "confirm_write" to true)))
        }
    }
    @Test fun destructiveRestoresNeedBothGates() {
        assertNotNull(DebugProbeContract.rejection(mapOf("ziprestore" to "file.zip", "confirm_write" to true)))
        assertNull(DebugProbeContract.rejection(mapOf("ziprestore" to "file.zip", "confirm_write" to true, "confirm_clear" to true)))
        assertNotNull(DebugProbeContract.rejection(mapOf("file" to "prefs.json", "clear" to true, "confirm_write" to true)))
        assertNull(DebugProbeContract.rejection(mapOf("file" to "prefs.json", "clear" to true, "confirm_write" to true, "confirm_clear" to true)))
    }
    @Test fun mixedEmptyAndWronglyTypedProbesReject() {
        assertNotNull(DebugProbeContract.rejection(emptyMap()))
        assertNotNull(DebugProbeContract.rejection(mapOf("params" to true, "alias" to "pkg", "confirm_write" to true)))
        assertNotNull(DebugProbeContract.rejection(mapOf("alias" to "", "confirm_write" to true)))
        assertNotNull(DebugProbeContract.rejection(mapOf("params" to "true")))
        assertNotNull(DebugProbeContract.rejection(mapOf("params" to true, "clear" to false)))
        assertNotNull(DebugProbeContract.rejection(mapOf("params" to true, "unknown" to true)))
    }
}
