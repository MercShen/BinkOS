package com.github.gezimos.inkos.helper

import android.content.Context
import com.github.gezimos.inkos.data.Prefs
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ZIP 版全量备份：
 *   prefs.json   设置（v1 信封）
 *   files/...    应用私有文件（自定义字体、壁纸、通知历史等）
 *
 * 恢复时先清理 prefs + filesDir，再把 files/ 拷回去，最后导入 prefs.json，
 * 所以换机/重装后字体和壁纸也能一起回来。
 */
object BackupZipHelper {
    const val PREFS_ENTRY = "prefs.json"
    const val FILES_DIR = "files"
    private const val CACHE_ZIP = "inkos_backup.zip"
    private const val CACHE_RESTORE_DIR = "inkos_restore"

    fun isZip(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(2)
            if (input.read(head) == 2) head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() else false
        }
    } catch (_: Exception) {
        false
    }

    /** 生成备份 zip（放在 cacheDir，调用方负责读取后删除）。 */
    fun createBackupZip(context: Context, prefs: Prefs): File {
        val out = File(context.cacheDir, CACHE_ZIP)
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry(PREFS_ENTRY))
            zos.write(prefs.saveToString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            val filesDir = context.filesDir
            addTree(zos, filesDir, FILES_DIR)
        }
        return out
    }

    private fun addTree(zos: ZipOutputStream, dir: File, prefix: String) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (file.name == CACHE_ZIP) return@forEach
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

    /**
     * 从 zip 恢复。返回 false 表示不是合法备份（没有 prefs.json 或解压失败）。
     * 路径穿越（`..`）和解压到临时目录之外的文件会被跳过。
     */
    fun restore(context: Context, prefs: Prefs, zipFile: File): Boolean {
        val tmp = File(context.cacheDir, CACHE_RESTORE_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        return try {
            val root = tmp.canonicalPath + File.separator
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name.isNotBlank() && !name.contains("..")) {
                        val target = File(tmp, name)
                        if (target.canonicalPath.startsWith(root)) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> zis.copyTo(out) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val prefsFile = File(tmp, PREFS_ENTRY)
            if (!prefsFile.exists()) return false
            val json = prefsFile.readText()
            prefs.clear()
            File(tmp, FILES_DIR).takeIf { it.exists() }?.copyRecursively(context.filesDir, overwrite = true)
            prefs.loadFromString(json)
            prefs.repairWallpaperPath()
            true
        } catch (_: Exception) {
            false
        } finally {
            tmp.deleteRecursively()
        }
    }
}
