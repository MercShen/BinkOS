package com.github.gezimos.inkos.helper

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pure, Android-free backup restore engine.
 *
 * ZIP restore ([stage] + [apply]) is destructive: prefs AND filesDir are cleared, so it always
 * snapshots raw prefs + filesDir first and rolls back on any failure. If the rollback itself fails,
 * the snapshot directory is kept and its path is reported (never preference values).
 *
 * Legacy JSON / theme imports are merges ([applyPrefsOnly]): they never clear settings and never
 * touch filesDir, but still snapshot prefs and roll back on failure.
 *
 * JVM tests drive it with a fake [RestoreTarget] so failure injection needs no Android/Robolectric.
 */
object BackupZipEngine {
    const val PREFS_ENTRY = "prefs.json"
    const val FILES_DIR = "files"
    const val PREFS_RAW_SNAPSHOT = "prefs_raw.json"

    private const val BUFFER = 8 * 1024
    private const val MAX_ENTRY_NAME = 512

    fun interface PrefsValidator {
        /** Returns null when [json] is a supported backup, otherwise a user-facing reason. */
        fun validate(json: String): String?
    }

    data class Limits(
        val maxEntries: Int = 512,
        val maxEntryBytes: Long = 64L * 1024 * 1024,
        val maxTotalBytes: Long = 256L * 1024 * 1024,
        val maxPrefsBytes: Long = 8L * 1024 * 1024
    )

    interface RestoreTarget {
        /** Copy of `sharedPrefs.all`; the engine keeps it for rollback. */
        fun snapshotPrefs(): Map<String, Any?>

        /** Clear preferences AND filesDir (Prefs.clear deletes files too). ZIP restore only. */
        fun clearAll()

        /** Import the validated prefs JSON (merge; does not clear). */
        fun loadPrefs(json: String)

        /** Put the snapshot back verbatim (raw value types). */
        fun restorePrefs(raw: Map<String, Any?>)

        /** Durably persist everything written so far. Throwing here triggers rollback. */
        fun commitPrefs()

        /** Post-import fix-ups (e.g. wallpaper path); must not commit. */
        fun repair()
    }

    data class Staged(val root: File, val prefsJson: String, val filesDir: File?)

    sealed class StageResult {
        data class Ready(val staged: Staged) : StageResult()
        data class Rejected(val reason: String) : StageResult()
    }

    sealed class ApplyResult {
        data object Applied : ApplyResult()
        /** [retainedSnapshot] is non-null when rollback failed and the old data copy was kept. */
        data class RolledBack(val reason: String, val retainedSnapshot: File? = null) : ApplyResult()
    }

    /**
     * Extract + validate without touching user data. Every entry (files and directories) is
     * normalized; unsafe or unexpected paths, duplicates, count/size overflows and zip bombs reject.
     */
    fun stage(
        zipFile: File,
        stagingRoot: File,
        limits: Limits = Limits(),
        validatePrefs: PrefsValidator
    ): StageResult {
        fun reject(reason: String): StageResult {
            stagingRoot.deleteRecursively()
            return StageResult.Rejected(reason)
        }

        if (!zipFile.isFile) return reject("Backup file is missing")
        stagingRoot.deleteRecursively()
        if (!stagingRoot.mkdirs() && !stagingRoot.isDirectory) return reject("Cannot create staging directory")

        var prefsJson: String? = null
        var entryCount = 0
        var totalBytes = 0L
        val seen = HashSet<String>()
        val stagingRootPath = stagingRoot.canonicalPath + File.separator

        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    entryCount++
                    if (entryCount > limits.maxEntries) return reject("Backup has too many entries (limit ${limits.maxEntries})")

                    val isDirectory = entry.isDirectory
                    val rawName = if (isDirectory) entry.name.trimEnd('/') else entry.name
                    val declaredSize = entry.size
                    if (!isDirectory && declaredSize > limits.maxEntryBytes) {
                        return reject("Entry is too large: ${entry.name}")
                    }
                    val relative = normalizeEntry(rawName) ?: return reject("Unsafe entry path: ${entry.name}")
                    if (!seen.add(relative)) return reject("Duplicate entry: $relative")
                    if (!isDirectory && declaredSize > 0 && totalBytes + declaredSize > limits.maxTotalBytes) {
                        return reject("Backup is too large")
                    }

                    if (isDirectory) {
                        if (relative != FILES_DIR && !relative.startsWith("$FILES_DIR/")) {
                            return reject("Unexpected directory entry: $relative")
                        }
                        File(stagingRoot, relative).mkdirs()
                        zis.closeEntry()
                        continue
                    }

                    when {
                        relative == PREFS_ENTRY -> {
                            val bytes = readLimited(zis, limits.maxPrefsBytes, limits.maxTotalBytes - totalBytes)
                                ?: return reject("prefs.json is missing or too large")
                            totalBytes += bytes.size
                            prefsJson = String(bytes, Charsets.UTF_8)
                        }
                        relative.startsWith("$FILES_DIR/") -> {
                            val rest = relative.removePrefix("$FILES_DIR/")
                            if (rest.isEmpty()) return reject("Invalid files entry")
                            val targetFile = File(stagingRoot, "$FILES_DIR/$rest")
                            val canonical = targetFile.canonicalPath
                            if (!canonical.startsWith(stagingRootPath)) return reject("Unsafe entry path: ${entry.name}")
                            targetFile.parentFile?.mkdirs()
                            val copied = copyLimited(zis, targetFile, limits.maxEntryBytes, limits.maxTotalBytes - totalBytes)
                                ?: return reject("Entry is too large or unreadable: ${entry.name}")
                            totalBytes += copied
                        }
                        else -> return reject("Unexpected entry: $relative")
                    }
                    zis.closeEntry()
                }
            }
        } catch (e: Exception) {
            stagingRoot.deleteRecursively()
            return StageResult.Rejected("Invalid or corrupted backup archive")
        }

        val json = prefsJson ?: return reject("Backup does not contain $PREFS_ENTRY")
        validatePrefs.validate(json)?.let { reason ->
            stagingRoot.deleteRecursively()
            return StageResult.Rejected(reason)
        }
        val stagedFiles = File(stagingRoot, FILES_DIR).takeIf { it.isDirectory }
        return StageResult.Ready(Staged(stagingRoot, json, stagedFiles))
    }

    /**
     * Destructive ZIP commit: snapshot raw prefs + filesDir, clear/copy/import, repair, then commit.
     * Any failure rolls back; if rollback fails the snapshot is retained for manual recovery.
     */
    fun apply(
        staged: Staged,
        filesDir: File,
        snapshotDir: File,
        target: RestoreTarget
    ): ApplyResult {
        snapshotDir.deleteRecursively()
        if (!snapshotDir.mkdirs() && !snapshotDir.isDirectory) {
            staged.root.deleteRecursively()
            return ApplyResult.RolledBack("Cannot create snapshot directory")
        }
        val filesSnapshot = File(snapshotDir, "files")

        val rawPrefs: Map<String, Any?>
        try {
            rawPrefs = target.snapshotPrefs()
            copyTree(filesDir, filesSnapshot)
            // Persist raw prefs next to the files snapshot BEFORE any mutation (manual recovery).
            writeRawPrefsSnapshot(snapshotDir, rawPrefs)
        } catch (e: Exception) {
            // Nothing has been mutated yet; the original data is still in place.
            snapshotDir.deleteRecursively()
            staged.root.deleteRecursively()
            return ApplyResult.RolledBack("Cannot snapshot current data: ${e.message ?: "unknown"}")
        }

        var keepSnapshot = false
        return try {
            target.clearAll()
            clearDirectory(filesDir)
            staged.filesDir?.let { copyTree(it, filesDir) }
            target.loadPrefs(staged.prefsJson)
            target.repair()
            target.commitPrefs()
            ApplyResult.Applied
        } catch (e: Exception) {
            val primary = e.message ?: e.javaClass.simpleName
            val rollbackError = try {
                target.clearAll()
                clearDirectory(filesDir)
                if (filesSnapshot.isDirectory) copyTree(filesSnapshot, filesDir)
                target.restorePrefs(rawPrefs)
                target.commitPrefs()
                null
            } catch (rollback: Exception) {
                keepSnapshot = true
                rollback.message ?: rollback.javaClass.simpleName
            }
            if (rollbackError == null) {
                ApplyResult.RolledBack("Restore failed ($primary); previous data restored")
            } else {
                ApplyResult.RolledBack(
                    "Restore failed ($primary); rollback also failed ($rollbackError). " +
                        "Previous data kept at ${snapshotDir.absolutePath}",
                    retainedSnapshot = snapshotDir
                )
            }
        } finally {
            staged.root.deleteRecursively()
            if (!keepSnapshot) snapshotDir.deleteRecursively()
        }
    }

    /**
     * Merge-only commit used by legacy JSON and theme import: no clearing, filesDir untouched.
     * Snapshots prefs and rolls back on failure (including a failed commit). When [snapshotDir] is
     * supplied, the raw prefs snapshot is persisted there before the action; on rollback failure the
     * directory is kept and its path reported.
     */
    fun applyPrefsOnly(
        target: RestoreTarget,
        snapshotDir: File? = null,
        action: () -> Unit
    ): ApplyResult {
        val rawPrefs: Map<String, Any?> = try {
            target.snapshotPrefs()
        } catch (e: Exception) {
            return ApplyResult.RolledBack("Cannot snapshot current settings: ${e.message ?: "unknown"}")
        }
        var keepSnapshot = false
        if (snapshotDir != null) {
            snapshotDir.deleteRecursively()
            val created = snapshotDir.mkdirs() || snapshotDir.isDirectory
            if (!created) return ApplyResult.RolledBack("Cannot create recovery snapshot directory")
            try {
                writeRawPrefsSnapshot(snapshotDir, rawPrefs)
            } catch (e: Exception) {
                snapshotDir.deleteRecursively()
                return ApplyResult.RolledBack("Cannot write settings snapshot: ${e.message ?: "unknown"}")
            }
        }
        return try {
            action()
            target.commitPrefs()
            ApplyResult.Applied
        } catch (e: Exception) {
            val primary = e.message ?: e.javaClass.simpleName
            val rollbackError = try {
                target.restorePrefs(rawPrefs)
                target.commitPrefs()
                null
            } catch (rollback: Exception) {
                keepSnapshot = true
                rollback.message ?: rollback.javaClass.simpleName
            }
            if (rollbackError == null) {
                ApplyResult.RolledBack("Import failed ($primary); previous settings restored")
            } else {
                val suffix = if (snapshotDir != null) {
                    " Previous settings kept at ${snapshotDir.absolutePath}"
                } else ""
                ApplyResult.RolledBack(
                    "Import failed ($primary); rollback also failed ($rollbackError).$suffix",
                    retainedSnapshot = if (snapshotDir != null && keepSnapshot) snapshotDir else null
                )
            }
        } finally {
            if (snapshotDir != null && !keepSnapshot) snapshotDir.deleteRecursively()
        }
    }

    /**
     * Restore a persisted recovery point ([PREFS_RAW_SNAPSHOT] + optional files/) after an explicit
     * confirmation. Current state is snapshotted into [safetySnapshotDir] first so a failed recovery
     * can roll back; on rollback failure that safety snapshot is retained and reported.
     * The point directory itself is never deleted here.
     */
    fun recoverFromSnapshot(
        pointDir: File,
        filesDir: File,
        target: RestoreTarget,
        safetySnapshotDir: File
    ): ApplyResult {
        val raw = readRawPrefsSnapshot(pointDir)
            ?: return ApplyResult.RolledBack("Recovery snapshot is unreadable or incomplete")

        safetySnapshotDir.deleteRecursively()
        if (!safetySnapshotDir.mkdirs() && !safetySnapshotDir.isDirectory) {
            return ApplyResult.RolledBack("Cannot create safety snapshot directory")
        }
        val currentRaw: Map<String, Any?>
        try {
            currentRaw = target.snapshotPrefs()
            copyTree(filesDir, File(safetySnapshotDir, "files"))
            writeRawPrefsSnapshot(safetySnapshotDir, currentRaw)
        } catch (e: Exception) {
            safetySnapshotDir.deleteRecursively()
            return ApplyResult.RolledBack("Cannot snapshot current data: ${e.message ?: "unknown"}")
        }

        var keepSafety = false
        return try {
            target.clearAll()
            clearDirectory(filesDir)
            File(pointDir, FILES_DIR).takeIf { it.isDirectory }?.let { copyTree(it, filesDir) }
            target.restorePrefs(raw)
            target.repair()
            target.commitPrefs()
            ApplyResult.Applied
        } catch (e: Exception) {
            val primary = e.message ?: e.javaClass.simpleName
            val rollbackError = try {
                target.clearAll()
                clearDirectory(filesDir)
                File(safetySnapshotDir, "files").takeIf { it.isDirectory }?.let { copyTree(it, filesDir) }
                target.restorePrefs(currentRaw)
                target.commitPrefs()
                null
            } catch (rollback: Exception) {
                keepSafety = true
                rollback.message ?: rollback.javaClass.simpleName
            }
            if (rollbackError == null) {
                ApplyResult.RolledBack("Recovery failed ($primary); current data restored")
            } else {
                ApplyResult.RolledBack(
                    "Recovery failed ($primary); rollback also failed ($rollbackError). " +
                        "Current data kept at ${safetySnapshotDir.absolutePath}",
                    retainedSnapshot = safetySnapshotDir
                )
            }
        } finally {
            if (!keepSafety) safetySnapshotDir.deleteRecursively()
        }
    }

    /** Writes raw preferences as typed JSON (no logging of values). */
    fun writeRawPrefsSnapshot(dir: File, raw: Map<String, Any?>): File {
        val root = JsonObject()
        raw.forEach { (key, value) ->
            val node = JsonObject()
            when (value) {
                is Boolean -> {
                    node.addProperty("type", "boolean")
                    node.addProperty("value", value)
                }
                is Int -> {
                    node.addProperty("type", "integer")
                    node.addProperty("value", value)
                }
                is Long -> {
                    node.addProperty("type", "long")
                    node.addProperty("value", value)
                }
                is Float -> {
                    node.addProperty("type", "float")
                    node.addProperty("value", value)
                }
                is String -> {
                    node.addProperty("type", "string")
                    node.addProperty("value", value)
                }
                is Set<*> -> {
                    node.addProperty("type", "set")
                    val array = JsonArray()
                    value.filterIsInstance<String>().forEach { array.add(it) }
                    node.add("value", array)
                }
                else -> return@forEach
            }
            root.add(key, node)
        }
        val out = File(dir, PREFS_RAW_SNAPSHOT)
        out.writeText(root.toString(), Charsets.UTF_8)
        return out
    }

    /** Reads [PREFS_RAW_SNAPSHOT] back into typed raw values, or null when missing/broken. */
    fun readRawPrefsSnapshot(dir: File): Map<String, Any?>? {
        val file = File(dir, PREFS_RAW_SNAPSHOT)
        if (!file.isFile) return null
        return try {
            val root = JsonParser().parse(file.readText(Charsets.UTF_8)).asJsonObject
            val out = HashMap<String, Any?>()
            root.entrySet().forEach { (key, node) ->
                if (!node.isJsonObject) return@forEach
                val obj = node.asJsonObject
                val type = obj.get("type")?.asString ?: return@forEach
                val value = obj.get("value") ?: return@forEach
                when (type) {
                    "boolean" -> out[key] = value.asBoolean
                    "integer" -> out[key] = value.asInt
                    "long" -> out[key] = value.asLong
                    "float" -> out[key] = value.asFloat
                    "string" -> out[key] = value.asString
                    "set" -> out[key] = value.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }.toSet()
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    fun discard(staged: Staged) {
        staged.root.deleteRecursively()
    }

    /** Normalized archive-relative path, or null when unsafe/not allowed. */
    fun normalizeEntry(name: String): String? {
        if (name.isBlank() || name.length > MAX_ENTRY_NAME) return null
        if (name.contains('\\') || name.contains('\u0000')) return null
        if (name.startsWith('/')) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(name)) return null
        val parts = name.split('/')
        if (parts.any { it == ".." }) return null
        // Collapse empty and "." segments (e.g. "files//a" -> "files/a") so duplicates are caught.
        val normalized = parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        return normalized.ifEmpty { null }
    }

    private fun readLimited(input: InputStream, maxBytes: Long, remainingTotal: Long): ByteArray? {
        val cap = minOf(maxBytes, remainingTotal, Int.MAX_VALUE.toLong())
        if (cap <= 0) return null
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > cap) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun copyLimited(input: InputStream, target: File, maxEntryBytes: Long, remainingTotal: Long): Long? {
        val cap = minOf(maxEntryBytes, remainingTotal)
        if (cap <= 0) return null
        var total = 0L
        target.outputStream().buffered().use { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > cap) return null
                out.write(buffer, 0, read)
            }
        }
        return total
    }

    fun clearDirectory(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) child.deleteRecursively() else child.delete()
        }
    }

    fun copyTree(source: File, destination: File) {
        if (!source.exists()) return
        if (source.isFile) {
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
            return
        }
        destination.mkdirs()
        source.listFiles()?.sortedBy { it.name }?.forEach { child ->
            copyTree(child, File(destination, child.name))
        }
    }
}
