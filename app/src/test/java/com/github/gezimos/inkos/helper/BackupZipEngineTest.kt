package com.github.gezimos.inkos.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupZipEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val validJson = "{\"type\":\"inkos_full\",\"version\":1,\"prefs\":{\"key\":\"value\"}}"

    private fun makeZip(vararg entries: Pair<String, ByteArray>): File {
        val file = tmp.newFile("backup-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }

    private fun okValidator() = BackupZipEngine.PrefsValidator { null }

    private fun stageOf(
        zip: File,
        limits: BackupZipEngine.Limits = BackupZipEngine.Limits(),
        validator: BackupZipEngine.PrefsValidator = okValidator()
    ): BackupZipEngine.StageResult =
        BackupZipEngine.stage(zip, File(tmp.root, "staging-${System.nanoTime()}"), limits, validator)

    private class FakeTarget(
        initial: Map<String, Any?> = mapOf("keep" to "original", "count" to 7),
        private val failLoad: Boolean = false,
        private val failRepair: Boolean = false,
        private val failCommitTimes: Int = 0,
        private val failRestore: Boolean = false
    ) : BackupZipEngine.RestoreTarget {
        var prefs: Map<String, Any?> = initial
        var clearCalls = 0
        var commitCalls = 0
        var repaired = false
        private var commitsToFail = failCommitTimes

        override fun snapshotPrefs(): Map<String, Any?> = prefs.mapValues { (_, v) ->
            if (v is Set<*>) v.filterIsInstance<String>().toSet() else v
        }

        override fun clearAll() {
            clearCalls++
            prefs = emptyMap()
        }

        override fun loadPrefs(json: String) {
            if (failLoad) throw IllegalStateException("injected load failure")
            prefs = mapOf("imported" to json)
        }

        override fun restorePrefs(raw: Map<String, Any?>) {
            if (failRestore) throw IllegalStateException("injected restore failure")
            prefs = raw
        }

        override fun commitPrefs() {
            commitCalls++
            if (commitsToFail > 0) {
                commitsToFail--
                throw IllegalStateException("injected commit failure")
            }
        }

        override fun repair() {
            if (failRepair) throw IllegalStateException("injected repair failure")
            repaired = true
        }
    }

    @Test
    fun stagesPrefsAndFilesFromValidArchive() {
        val zip = makeZip(
            "prefs.json" to validJson.toByteArray(),
            "files/fonts/custom.ttf" to byteArrayOf(1, 2, 3)
        )
        val result = stageOf(zip)
        assertTrue(result is BackupZipEngine.StageResult.Ready)
        val staged = (result as BackupZipEngine.StageResult.Ready).staged
        assertEquals(validJson, staged.prefsJson)
        assertNotNull(staged.filesDir)
        assertTrue(File(staged.filesDir, "fonts/custom.ttf").exists())
        BackupZipEngine.discard(staged)
        assertFalse(staged.root.exists())
    }

    @Test
    fun acceptsSafeDirectoryEntries() {
        val zip = makeZip(
            "files/" to ByteArray(0),
            "files/sub/" to ByteArray(0),
            "files/sub/a.txt" to byteArrayOf(1),
            "prefs.json" to validJson.toByteArray()
        )
        assertTrue(stageOf(zip) is BackupZipEngine.StageResult.Ready)
    }

    @Test
    fun rejectsArchiveWithoutPrefsEntry() {
        val zip = makeZip("files/a.txt" to byteArrayOf(1))
        assertTrue(stageOf(zip) is BackupZipEngine.StageResult.Rejected)
    }

    @Test
    fun rejectsUnsafePathsAndUnexpectedEntries() {
        val unsafe = listOf(
            "../evil.txt",
            "/absolute.txt",
            "C:/windows.txt",
            "files/../escape.txt",
            "files/a\\b.txt",
            "other.json",
            "../evil/"
        )
        unsafe.forEach { name ->
            val result = stageOf(makeZip(name to byteArrayOf(1)))
            assertTrue("expected reject for $name", result is BackupZipEngine.StageResult.Rejected)
        }
    }

    @Test
    fun rejectsUnexpectedDirectoryEntry() {
        val zip = makeZip(
            "prefs.json" to validJson.toByteArray(),
            "other/" to ByteArray(0)
        )
        assertTrue(stageOf(zip) is BackupZipEngine.StageResult.Rejected)
    }

    @Test
    fun rejectsNormalizedDuplicates() {
        val zip = makeZip(
            "prefs.json" to validJson.toByteArray(),
            "files/a.txt" to byteArrayOf(1),
            "files//a.txt" to byteArrayOf(2)
        )
        val result = stageOf(zip)
        assertTrue(result is BackupZipEngine.StageResult.Rejected)
        assertTrue((result as BackupZipEngine.StageResult.Rejected).reason.contains("Duplicate"))
    }

    @Test
    fun rejectsTooManyEntriesIncludingDirectories() {
        val zip = makeZip(
            "d1/" to ByteArray(0),
            "d2/" to ByteArray(0),
            "d3/" to ByteArray(0),
            "prefs.json" to validJson.toByteArray()
        )
        assertTrue(stageOf(zip, BackupZipEngine.Limits(maxEntries = 3)) is BackupZipEngine.StageResult.Rejected)
    }

    @Test
    fun rejectsTooLargeEntry() {
        val zip = makeZip(
            "prefs.json" to validJson.toByteArray(),
            "files/big.bin" to ByteArray(4096) { 1 }
        )
        assertTrue(stageOf(zip, BackupZipEngine.Limits(maxEntryBytes = 1024)) is BackupZipEngine.StageResult.Rejected)
    }

    @Test
    fun rejectsTooLargeTotal() {
        val zip = makeZip(
            "prefs.json" to validJson.toByteArray(),
            "files/a.bin" to ByteArray(200) { 1 },
            "files/b.bin" to ByteArray(200) { 1 }
        )
        assertTrue(stageOf(zip, BackupZipEngine.Limits(maxTotalBytes = 300)) is BackupZipEngine.StageResult.Rejected)
    }

    @Test
    fun validationFailureRejectsBeforeTouchingUserData() {
        val zip = makeZip("prefs.json" to validJson.toByteArray())
        val target = FakeTarget()
        val stagedRoot = File(tmp.root, "staging")
        val result = BackupZipEngine.stage(
            zip, stagedRoot, BackupZipEngine.Limits(),
            BackupZipEngine.PrefsValidator { "bad backup" }
        )
        assertTrue(result is BackupZipEngine.StageResult.Rejected)
        assertEquals("bad backup", (result as BackupZipEngine.StageResult.Rejected).reason)
        assertEquals(0, target.clearCalls)
        assertFalse(stagedRoot.exists())
    }

    @Test
    fun applyCopiesFilesAndCommitsInsideEngine() {
        val zip = makeZip(
            "prefs.json" to validJson.toByteArray(),
            "files/new.txt" to "new".toByteArray()
        )
        val staged = (stageOf(zip) as BackupZipEngine.StageResult.Ready).staged
        val filesDir = tmp.newFolder("files")
        File(filesDir, "old.txt").writeText("old")
        val target = FakeTarget()
        val snapshot = File(tmp.root, "snapshot")
        val outcome = BackupZipEngine.apply(staged, filesDir, snapshot, target)
        assertTrue(outcome is BackupZipEngine.ApplyResult.Applied)
        assertTrue(target.repaired)
        assertEquals(1, target.commitCalls)
        assertEquals(1, target.clearCalls)
        assertFalse(File(filesDir, "old.txt").exists())
        assertEquals("new", File(filesDir, "new.txt").readText())
        assertTrue(target.prefs.containsKey("imported"))
        assertFalse(staged.root.exists())
        assertFalse(snapshot.exists())
    }

    @Test
    fun loadFailureRollsBackPrefsAndFiles() {
        val zip = makeZip(
            "prefs.json" to validJson.toByteArray(),
            "files/new.txt" to "new".toByteArray()
        )
        val staged = (stageOf(zip) as BackupZipEngine.StageResult.Ready).staged
        val filesDir = tmp.newFolder("files")
        File(filesDir, "original.txt").writeText("original")
        val target = FakeTarget(failLoad = true)
        val before = target.prefs
        val snapshot = File(tmp.root, "snapshot")
        val outcome = BackupZipEngine.apply(staged, filesDir, snapshot, target)
        assertTrue(outcome is BackupZipEngine.ApplyResult.RolledBack)
        assertEquals(before, target.prefs)
        assertEquals("original", File(filesDir, "original.txt").readText())
        assertFalse(File(filesDir, "new.txt").exists())
        assertFalse(staged.root.exists())
        assertFalse(snapshot.exists())
    }

    @Test
    fun commitFailureRollsBackAndCommitIsChecked() {
        val zip = makeZip("prefs.json" to validJson.toByteArray())
        val staged = (stageOf(zip) as BackupZipEngine.StageResult.Ready).staged
        val filesDir = tmp.newFolder("files")
        val target = FakeTarget(failCommitTimes = 1)
        val before = target.prefs
        val outcome = BackupZipEngine.apply(staged, filesDir, File(tmp.root, "snapshot"), target)
        assertTrue(outcome is BackupZipEngine.ApplyResult.RolledBack)
        assertEquals(before, target.prefs)
        assertEquals(2, target.commitCalls)
    }

    @Test
    fun repairFailureRollsBack() {
        val zip = makeZip("prefs.json" to validJson.toByteArray())
        val staged = (stageOf(zip) as BackupZipEngine.StageResult.Ready).staged
        val filesDir = tmp.newFolder("files")
        val target = FakeTarget(failRepair = true)
        val before = target.prefs
        val outcome = BackupZipEngine.apply(staged, filesDir, File(tmp.root, "snapshot"), target)
        assertTrue(outcome is BackupZipEngine.ApplyResult.RolledBack)
        assertEquals(before, target.prefs)
    }

    @Test
    fun failedRollbackRetainsSnapshotForRecovery() {
        val zip = makeZip(
            "prefs.json" to validJson.toByteArray(),
            "files/new.txt" to "new".toByteArray()
        )
        val staged = (stageOf(zip) as BackupZipEngine.StageResult.Ready).staged
        val filesDir = tmp.newFolder("files")
        File(filesDir, "original.txt").writeText("original")
        val target = FakeTarget(failLoad = true, failRestore = true)
        val snapshot = File(tmp.root, "snapshot")
        val rawBefore = target.prefs
        val outcome = BackupZipEngine.apply(staged, filesDir, snapshot, target)
        assertTrue(outcome is BackupZipEngine.ApplyResult.RolledBack)
        val retained = (outcome as BackupZipEngine.ApplyResult.RolledBack).retainedSnapshot
        assertNotNull(retained)
        assertTrue(retained!!.isDirectory)
        assertTrue(File(retained, "files/original.txt").exists())
        assertTrue(File(retained, BackupZipEngine.PREFS_RAW_SNAPSHOT).isFile)
        assertEquals(rawBefore, BackupZipEngine.readRawPrefsSnapshot(retained))
        assertTrue(outcome.reason.contains(retained.absolutePath))
        assertFalse(staged.root.exists())
        retained.deleteRecursively()
    }

    @Test
    fun recoverFromSnapshotRestoresPrefsAndFiles() {
        val point = tmp.newFolder("recovery_point")
        val recovered = mapOf<String, Any?>("restored" to "yes", "n" to 3, "ratio" to 1.5f)
        BackupZipEngine.writeRawPrefsSnapshot(point, recovered)
        File(point, "files").mkdirs()
        File(point, "files/a.txt").writeText("from-point")
        val filesDir = tmp.newFolder("files")
        File(filesDir, "old.txt").writeText("old")
        val target = FakeTarget()
        val safety = File(tmp.root, "safety")
        val outcome = BackupZipEngine.recoverFromSnapshot(point, filesDir, target, safety)
        assertTrue(outcome is BackupZipEngine.ApplyResult.Applied)
        assertEquals(recovered, target.prefs)
        assertEquals("from-point", File(filesDir, "a.txt").readText())
        assertFalse(File(filesDir, "old.txt").exists())
        assertFalse(safety.exists())
        assertTrue(point.isDirectory)
    }

    @Test
    fun recoverCommitFailureRollsBackToSafetySnapshot() {
        val point = tmp.newFolder("recovery_point")
        BackupZipEngine.writeRawPrefsSnapshot(point, mapOf("restored" to "yes"))
        File(point, "files").mkdirs()
        File(point, "files/a.txt").writeText("from-point")
        val filesDir = tmp.newFolder("files")
        File(filesDir, "original.txt").writeText("original")
        val target = FakeTarget(failCommitTimes = 1)
        val before = target.prefs
        val safety = File(tmp.root, "safety")
        val outcome = BackupZipEngine.recoverFromSnapshot(point, filesDir, target, safety)
        assertTrue(outcome is BackupZipEngine.ApplyResult.RolledBack)
        assertEquals(before, target.prefs)
        assertEquals("original", File(filesDir, "original.txt").readText())
        assertFalse(File(filesDir, "a.txt").exists())
        assertFalse(safety.exists())
    }

    @Test
    fun recoverRollbackFailureRetainsSafetySnapshot() {
        val point = tmp.newFolder("recovery_point")
        BackupZipEngine.writeRawPrefsSnapshot(point, mapOf("restored" to "yes"))
        val filesDir = tmp.newFolder("files")
        File(filesDir, "original.txt").writeText("original")
        val target = FakeTarget(failCommitTimes = 1, failRestore = true)
        val safety = File(tmp.root, "safety")
        val outcome = BackupZipEngine.recoverFromSnapshot(point, filesDir, target, safety)
        assertTrue(outcome is BackupZipEngine.ApplyResult.RolledBack)
        val retained = (outcome as BackupZipEngine.ApplyResult.RolledBack).retainedSnapshot
        assertNotNull(retained)
        assertTrue(File(retained!!, BackupZipEngine.PREFS_RAW_SNAPSHOT).isFile)
        assertTrue(File(retained, "files/original.txt").exists())
        assertTrue(outcome.reason.contains(retained.absolutePath))
        retained.deleteRecursively()
    }

    @Test
    fun mergeSnapshotRetainedWhenRollbackFails() {
        val snapshot = File(tmp.root, "merge-snapshot")
        val target = FakeTarget(failCommitTimes = 1, failRestore = true)
        val before = target.prefs
        val outcome = BackupZipEngine.applyPrefsOnly(target, snapshot) {
            target.loadPrefs(validJson)
        }
        assertTrue(outcome is BackupZipEngine.ApplyResult.RolledBack)
        assertEquals(snapshot, (outcome as BackupZipEngine.ApplyResult.RolledBack).retainedSnapshot)
        assertEquals(before, BackupZipEngine.readRawPrefsSnapshot(snapshot))
        snapshot.deleteRecursively()
    }

    @Test
    fun mergeSuccessDeletesRecoverySnapshot() {
        val snapshot = File(tmp.root, "merge-snapshot")
        val target = FakeTarget()
        val outcome = BackupZipEngine.applyPrefsOnly(target, snapshot) {
            target.loadPrefs(validJson)
        }
        assertTrue(outcome is BackupZipEngine.ApplyResult.Applied)
        assertFalse(snapshot.exists())
    }

    @Test
    fun legacyJsonMergePreservesFilesAndCommits() {
        val filesDir = tmp.newFolder("files")
        File(filesDir, "keep.txt").writeText("keep")
        val target = FakeTarget()
        val outcome = BackupZipEngine.applyPrefsOnly(target) {
            target.loadPrefs(validJson)
        }
        assertTrue(outcome is BackupZipEngine.ApplyResult.Applied)
        assertEquals(0, target.clearCalls)
        assertEquals("keep", File(filesDir, "keep.txt").readText())
        assertEquals(1, target.commitCalls)
        assertTrue(target.prefs.containsKey("imported"))
    }

    @Test
    fun legacyMergeFailureRollsBackPrefsAndKeepsFiles() {
        val filesDir = tmp.newFolder("files")
        File(filesDir, "keep.txt").writeText("keep")
        val target = FakeTarget()
        val before = target.prefs
        val outcome = BackupZipEngine.applyPrefsOnly(target) {
            throw IllegalStateException("injected merge failure")
        }
        assertTrue(outcome is BackupZipEngine.ApplyResult.RolledBack)
        assertEquals(before, target.prefs)
        assertEquals("keep", File(filesDir, "keep.txt").readText())
        assertTrue(target.commitCalls >= 1)
    }

    @Test
    fun legacyMergeCommitFailureRollsBackPrefsAndKeepsFiles() {
        val filesDir = tmp.newFolder("files")
        File(filesDir, "keep.txt").writeText("keep")
        val target = FakeTarget(failCommitTimes = 1)
        val before = target.prefs
        val outcome = BackupZipEngine.applyPrefsOnly(target) {
            target.loadPrefs(validJson)
        }
        assertTrue(outcome is BackupZipEngine.ApplyResult.RolledBack)
        assertEquals(before, target.prefs)
        assertEquals("keep", File(filesDir, "keep.txt").readText())
        assertEquals(2, target.commitCalls)
    }

    @Test
    fun normalizeEntryRejectsTraversalAndAbsolutePaths() {
        assertEquals("files/a.txt", BackupZipEngine.normalizeEntry("files/a.txt"))
        assertEquals(null, BackupZipEngine.normalizeEntry("../a.txt"))
        assertEquals(null, BackupZipEngine.normalizeEntry("/a.txt"))
        assertEquals(null, BackupZipEngine.normalizeEntry("C:\\a.txt"))
        assertEquals(null, BackupZipEngine.normalizeEntry("files/../../a.txt"))
    }
}
