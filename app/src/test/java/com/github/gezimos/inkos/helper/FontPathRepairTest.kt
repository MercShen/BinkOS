package com.github.gezimos.inkos.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FontPathRepairTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun filesDir(): File = tmp.newFolder("files")

    private fun font(dir: File, name: String): File =
        File(dir, name).apply { writeText("font-bytes") }

    private fun repair(
        dir: File,
        paths: Set<String> = emptySet(),
        map: Map<String, String> = emptyMap(),
        legacy: String? = null,
        slots: Map<String, String> = emptyMap()
    ): FontPathRepair.Result? = FontPathRepair.repair(
        FontPathRepair.Input(
            filesDir = dir,
            customFontPaths = paths,
            pathMap = map,
            legacyPath = legacy,
            slotFonts = slots
        )
    )

    @Test
    fun staleAbsolutePathIsRemappedToFilesDirFile() {
        val dir = filesDir()
        val font = font(dir, "fusion.ttf")
        val result = repair(
            dir,
            paths = setOf("/data/user/0/other.pkg/files/fusion.ttf"),
            map = mapOf("clock" to "/data/user/0/other.pkg/files/fusion.ttf"),
            slots = mapOf("clock" to "Custom")
        )
        assertNotNull(result)
        assertEquals(font.absolutePath, result!!.pathMap["clock"])
        assertEquals(setOf(font.absolutePath), result.customFontPaths)
    }

    @Test
    fun missingDateSlotFallsBackToLegacyPath() {
        val dir = filesDir()
        val font = font(dir, "fusion.ttf")
        val result = repair(
            dir,
            paths = setOf(font.absolutePath),
            map = mapOf("clock" to font.absolutePath),
            legacy = font.absolutePath,
            slots = mapOf("clock" to "Custom", "date" to "Custom")
        )
        assertNotNull(result)
        assertEquals(font.absolutePath, result!!.pathMap["date"])
    }

    @Test
    fun missingSlotFallsBackToUniversalCustomPath() {
        val dir = filesDir()
        val font = font(dir, "fusion.ttf")
        val result = repair(
            dir,
            paths = setOf(font.absolutePath),
            map = mapOf("universal" to font.absolutePath),
            slots = mapOf("universal" to "Custom", "date" to "Custom")
        )
        assertNotNull(result)
        assertEquals(font.absolutePath, result!!.pathMap["date"])
    }

    @Test
    fun missingSlotFallsBackToSingleImportedFont() {
        val dir = filesDir()
        val font = font(dir, "fusion.ttf")
        val result = repair(
            dir,
            paths = setOf(font.absolutePath),
            map = emptyMap(),
            slots = mapOf("date" to "Custom")
        )
        assertNotNull(result)
        assertEquals(font.absolutePath, result!!.pathMap["date"])
    }

    @Test
    fun doesNotInventFontWhenSeveralCandidatesExist() {
        val dir = filesDir()
        val a = font(dir, "a.ttf")
        val b = font(dir, "b.otf")
        val result = repair(
            dir,
            paths = setOf(a.absolutePath, b.absolutePath),
            map = emptyMap(),
            slots = mapOf("date" to "Custom")
        )
        assertNull(result)
    }

    @Test
    fun onlyTouchesSlotsWhoseFontIsCustom() {
        val dir = filesDir()
        font(dir, "fusion.ttf")
        val result = repair(
            dir,
            map = emptyMap(),
            slots = mapOf("date" to "PublicSans", "clock" to "System")
        )
        assertNull(result)
    }

    @Test
    fun healthyStateIsLeftAlone() {
        val dir = filesDir()
        val font = font(dir, "fusion.ttf")
        // A single font plus an explicit date path must not be rewritten.
        val result = repair(
            dir,
            paths = setOf(font.absolutePath),
            map = mapOf("date" to font.absolutePath),
            slots = mapOf("date" to "Custom")
        )
        assertNull(result)
    }

    @Test
    fun deadEntriesAreDropped() {
        val dir = filesDir()
        val result = repair(
            dir,
            paths = setOf("/data/user/0/app.binkos/files/gone.ttf"),
            map = mapOf("date" to "/data/user/0/app.binkos/files/gone.ttf"),
            slots = mapOf("date" to "Custom")
        )
        assertNotNull(result)
        assertTrue(result!!.pathMap.isEmpty())
        assertTrue(result.customFontPaths.isEmpty())
    }

    @Test
    fun relativePathsResolveAgainstFilesDir() {
        val dir = filesDir()
        val font = font(dir, "fusion.ttf")
        val result = repair(
            dir,
            paths = setOf("fusion.ttf"),
            map = mapOf("date" to "fusion.ttf"),
            slots = mapOf("date" to "Custom")
        )
        assertNotNull(result)
        assertEquals(font.absolutePath, result!!.pathMap["date"])
    }
}
