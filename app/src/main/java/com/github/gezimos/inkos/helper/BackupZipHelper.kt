package com.github.gezimos.inkos.helper

import android.content.Context
import android.net.Uri
import android.util.Log
import com.github.gezimos.inkos.data.Prefs
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Android side of backup/restore.
 *
 * The archive/rollback logic lives in [BackupZipEngine] (pure JVM). This object only bridges
 * content URIs, filesDir/prefs and the engine. All public I/O entries are serialized with one lock
 * and use unique temp paths, so concurrent imports/backups cannot delete each other's work.
 * Callers must run them off the main thread.
 */
object BackupZipHelper {
    const val PREFS_ENTRY = BackupZipEngine.PREFS_ENTRY
    const val FILES_DIR = BackupZipEngine.FILES_DIR

    private const val MAX_IMPORT_BYTES: Long = 300L * 1024 * 1024
    private const val MAX_THEME_BYTES: Long = 8L * 1024 * 1024
    private const val TAG = "BackupZip"

    private val backupLock = Any()

    data class Result(val ok: Boolean, val needsRestart: Boolean = false, val message: String? = null)

    /** Read-only metadata for a retained recovery point (never contains preference values). */
    data class RecoveryPoint(
        val id: String,
        val createdAtMs: Long,
        val prefsBytes: Long,
        val filesBytes: Long,
        val fileCount: Int
    )

    /**
     * Lists retained recovery points under noBackupFilesDir (read-only). There is intentionally no
     * automatic import: the caller/user must decide, then call [recoverPoint] with a confirmation.
     */
    fun listRecoveryPoints(context: Context): List<RecoveryPoint> = synchronized(backupLock) {
        val root = context.noBackupFilesDir
        root.listFiles()
            ?.filter { it.isDirectory && it.name.matches(RECOVERY_ID_PATTERN) }
            ?.filter { File(it, BackupZipEngine.PREFS_RAW_SNAPSHOT).isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { dir ->
                var filesBytes = 0L
                var fileCount = 0
                File(dir, BackupZipEngine.FILES_DIR).walkTopDown().forEach { child ->
                    if (child.isFile) {
                        filesBytes += child.length()
                        fileCount++
                    }
                }
                RecoveryPoint(
                    id = dir.name,
                    createdAtMs = dir.lastModified(),
                    prefsBytes = File(dir, BackupZipEngine.PREFS_RAW_SNAPSHOT).length(),
                    filesBytes = filesBytes,
                    fileCount = fileCount
                )
            }
            .orEmpty()
    }

    /**
     * Restores a retained recovery point after [confirm] returns true. Current state is snapshotted
     * under noBackupFilesDir first; a failed recovery rolls back and reports the kept safety copy.
     * The ID must be exactly a recovery directory directly under noBackupFilesDir (path guarded).
     */
    fun recoverPoint(
        context: Context,
        prefs: Prefs,
        id: String,
        confirm: () -> Boolean
    ): Result = synchronized(backupLock) {
        if (!id.matches(RECOVERY_ID_PATTERN)) return Result(false, message = "Invalid recovery id")
        val root = context.noBackupFilesDir.canonicalFile
        val point = File(root, id).canonicalFile
        if (point.parentFile?.canonicalPath != root.canonicalPath || !point.isDirectory) {
            return Result(false, message = "Recovery point not found")
        }
        if (!File(point, BackupZipEngine.PREFS_RAW_SNAPSHOT).isFile) {
            return Result(false, message = "Recovery point is incomplete")
        }
        if (!confirm()) return Result(false, message = "Recovery was not confirmed")
        val safety = newRecoveryDir(context)
        return when (
            val result = BackupZipEngine.recoverFromSnapshot(
                pointDir = point,
                filesDir = context.filesDir,
                target = AndroidRestoreTarget(prefs),
                safetySnapshotDir = safety
            )
        ) {
            is BackupZipEngine.ApplyResult.Applied -> Result(true, needsRestart = true)
            is BackupZipEngine.ApplyResult.RolledBack -> Result(false, message = result.reason)
        }
    }

    private val RECOVERY_ID_PATTERN = Regex("^recovery_[A-Za-z0-9-]{8,64}$")

    /** Recovery points live in noBackupFilesDir (NOT cacheDir / filesDir) so clears cannot remove them. */
    private fun newRecoveryDir(context: Context): File =
        File(context.noBackupFilesDir, "recovery_${UUID.randomUUID()}")


    fun isZip(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(2)
            if (input.read(head) == 2) head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() else false
        }
    } catch (_: Exception) {
        false
    }

    /** Generate a unique backup zip in cacheDir. Caller owns deletion; runs under [backupLock]. */
    fun createBackupZip(context: Context, prefs: Prefs): File = synchronized(backupLock) {
        val out = File.createTempFile("inkos_backup_", ".zip", context.cacheDir)
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry(PREFS_ENTRY))
            zos.write(prefs.saveToString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            addTree(zos, context.filesDir, FILES_DIR)
        }
        out
    }

    private fun addTree(zos: ZipOutputStream, dir: File, prefix: String) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            val entryName = "$prefix/${file.name}"
            if (file.isDirectory) {
                addTree(zos, file, entryName)
            } else {
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    /** ZIP restore for callers that cannot use the URI bridge (debug probe). I/O dispatcher only. */
    fun restore(context: Context, prefs: Prefs, zipFile: File): Boolean = synchronized(backupLock) {
        restoreZipLocked(context, prefs, zipFile).ok
    }

    /** Full backup to a content [uri]. I/O dispatcher only. */
    fun backupToUri(context: Context, prefs: Prefs, uri: Uri): Result = synchronized(backupLock) {
        var zip: File? = null
        try {
            zip = createBackupZip(context, prefs)
            val output = context.contentResolver.openOutputStream(uri)
                ?: return Result(false, message = "Cannot open destination file")
            output.use { out ->
                zip.inputStream().use { input -> input.copyTo(out) }
            }
            Result(true)
        } catch (e: Exception) {
            Result(false, message = e.message ?: e.javaClass.simpleName)
        } finally {
            zip?.delete()
        }
    }

    /**
     * Import ZIP or legacy JSON from a content [uri].
     * ZIP = destructive restore (snapshot + rollback); legacy JSON/theme = merge (preserves files).
     */
    fun importFromUri(context: Context, prefs: Prefs, uri: Uri): Result = synchronized(backupLock) {
        val tmp = File.createTempFile("inkos_import_", ".tmp", context.cacheDir)
        try {
            if (!copyToTemp(context, uri, tmp, MAX_IMPORT_BYTES)) {
                return Result(false, message = "Cannot read file or file is too large")
            }
            if (isZip(tmp)) {
                restoreZipLocked(context, prefs, tmp)
            } else {
                importLegacyJsonLocked(context, prefs, tmp)
            }
        } catch (e: Exception) {
            Result(false, message = e.message ?: e.javaClass.simpleName)
        } finally {
            tmp.delete()
        }
    }

    /** Theme-only export; no archive, no user data mutation. I/O dispatcher only. */
    fun exportThemeToUri(context: Context, prefs: Prefs, uri: Uri): Result = synchronized(backupLock) {
        try {
            val json = prefs.saveThemeToJson()
            val output = context.contentResolver.openOutputStream(uri)
                ?: return Result(false, message = "Cannot open destination file")
            output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            Result(true)
        } catch (e: Exception) {
            Result(false, message = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Theme-only import: merges prefs (snapshot + rollback), never clears settings or files. */
    fun importThemeFromUri(context: Context, prefs: Prefs, uri: Uri): Result = synchronized(backupLock) {
        val tmp = File.createTempFile("inkos_theme_", ".tmp", context.cacheDir)
        try {
            if (!copyToTemp(context, uri, tmp, MAX_THEME_BYTES)) {
                return Result(false, message = "Cannot read file or file is too large")
            }
            val json = tmp.readText(Charsets.UTF_8)
            when (val result = BackupZipEngine.applyPrefsOnly(AndroidRestoreTarget(prefs), newRecoveryDir(context)) {
                prefs.loadThemeFromJson(json)
            }) {
                is BackupZipEngine.ApplyResult.Applied -> Result(true, needsRestart = true)
                is BackupZipEngine.ApplyResult.RolledBack -> Result(false, message = result.reason)
            }
        } catch (e: Exception) {
            Result(false, message = e.message ?: e.javaClass.simpleName)
        } finally {
            tmp.delete()
        }
    }

    private fun restoreZipLocked(context: Context, prefs: Prefs, zipFile: File): Result {
        val staging = File(context.cacheDir, "inkos_restore_${UUID.randomUUID()}")
        val snapshot = newRecoveryDir(context)
        val staged = when (
            val result = BackupZipEngine.stage(
                zipFile = zipFile,
                stagingRoot = staging,
                validatePrefs = BackupZipEngine.PrefsValidator { json -> prefs.validateBackupString(json) }
            )
        ) {
            is BackupZipEngine.StageResult.Rejected -> return Result(false, message = result.reason)
            is BackupZipEngine.StageResult.Ready -> result.staged
        }
        return when (
            val applied = BackupZipEngine.apply(
                staged = staged,
                filesDir = context.filesDir,
                snapshotDir = snapshot,
                target = AndroidRestoreTarget(prefs)
            )
        ) {
            is BackupZipEngine.ApplyResult.Applied -> Result(true, needsRestart = true)
            is BackupZipEngine.ApplyResult.RolledBack -> Result(false, message = applied.reason)
        }
    }

    /** Legacy JSON = merge (old Main used loadFromString without clear); files are preserved. */
    private fun importLegacyJsonLocked(context: Context, prefs: Prefs, tmp: File): Result {
        val json = tmp.readText(Charsets.UTF_8)
        if (isThemePayload(json)) {
            return when (val result = BackupZipEngine.applyPrefsOnly(AndroidRestoreTarget(prefs), newRecoveryDir(context)) {
                prefs.loadThemeFromJson(json)
            }) {
                is BackupZipEngine.ApplyResult.Applied -> Result(true, needsRestart = true)
                is BackupZipEngine.ApplyResult.RolledBack -> Result(false, message = result.reason)
            }
        }
        prefs.validateBackupString(json)?.let { reason -> return Result(false, message = reason) }
        return when (val result = BackupZipEngine.applyPrefsOnly(AndroidRestoreTarget(prefs), newRecoveryDir(context)) {
            prefs.loadFromString(json)
        }) {
            is BackupZipEngine.ApplyResult.Applied -> Result(true, needsRestart = true)
            is BackupZipEngine.ApplyResult.RolledBack -> Result(false, message = result.reason)
        }
    }

    private fun isThemePayload(json: String): Boolean = try {
        val root = com.google.gson.JsonParser().parse(json)
        root.isJsonObject && root.asJsonObject.has("type") &&
            root.asJsonObject.get("type").isJsonPrimitive &&
            root.asJsonObject.getAsJsonPrimitive("type").asString == "inkos_theme"
    } catch (_: Exception) {
        false
    }

    private fun copyToTemp(context: Context, uri: Uri, dest: File, maxBytes: Long): Boolean {
        dest.delete()
        val input = context.contentResolver.openInputStream(uri) ?: return false
        return try {
            input.use { source ->
                FileOutputStream(dest).use { out ->
                    if (!copyLimited(source, out, maxBytes)) throw java.io.IOException("Import exceeds size limit")
                }
            }
            true
        } catch (_: Exception) {
            dest.delete()
            false
        }
    }

    private fun copyLimited(input: InputStream, out: FileOutputStream, maxBytes: Long): Boolean {
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            total += read
            if (total > maxBytes) return false
            out.write(buffer, 0, read)
        }
    }

    /** Bridges [BackupZipEngine.RestoreTarget] to Prefs; commits are checked, values never logged. */
    private class AndroidRestoreTarget(private val prefs: Prefs) : BackupZipEngine.RestoreTarget {
        override fun snapshotPrefs(): Map<String, Any?> =
            prefs.sharedPrefs.all.mapValues { (_, value) ->
                if (value is Set<*>) value.filterIsInstance<String>().toSet() else value
            }

        override fun clearAll() {
            // Prefs.clear() clears preferences AND filesDir, which is the ZIP restore semantics.
            prefs.clear()
        }

        override fun loadPrefs(json: String) {
            prefs.loadFromString(json)
        }

        override fun restorePrefs(raw: Map<String, Any?>) {
            val editor = prefs.sharedPrefs.edit().clear()
            raw.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            if (!editor.commit()) throw java.io.IOException("Cannot commit restored preferences")
        }

        override fun commitPrefs() {
            // commit persists the whole in-memory map; false means the rollback/restore cannot be trusted.
            val ok = prefs.sharedPrefs.edit().putBoolean("_sync_", false).remove("_sync_").commit()
            if (!ok) {
                Log.e(TAG, "SharedPreferences commit failed")
                throw IllegalStateException("SharedPreferences commit failed")
            }
        }

        override fun repair() {
            prefs.repairWallpaperPath()
        }
    }
}
