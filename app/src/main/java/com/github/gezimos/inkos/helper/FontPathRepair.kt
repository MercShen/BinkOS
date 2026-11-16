package com.github.gezimos.inkos.helper

import java.io.File

/**
 * Repairs custom-font bookkeeping after a full restore or an app start.
 *
 * The launcher stores custom fonts in three places:
 *  - `custom_font_paths`   : the set offered in the font picker (absolute paths);
 *  - `custom_font_path_map`: per-slot paths, e.g. `{"clock": "...", "date": "..."}`;
 *  - `custom_font_path`    : one legacy single-font path (kept for old backups).
 *
 * Paths are absolute, so a restored archive, a changed package id, or a deleted
 * font can leave a slot pointing at a file that no longer exists. When that
 * happens the renderer silently falls back to the system font for that slot only
 * (the classic "clock keeps the imported font, date does not" symptom).
 *
 * This object is Android-free so the repair rules can be unit-tested on the JVM.
 * It never invents a font when there is nothing to choose: a slot is only healed
 * from a path that still resolves (universal / legacy / the only imported font).
 */
object FontPathRepair {
    val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

    data class Input(
        val filesDir: File,
        /** `custom_font_paths` as stored (absolute or relative paths). */
        val customFontPaths: Set<String>,
        /** `custom_font_path_map` decoded. */
        val pathMap: Map<String, String>,
        /** Legacy `custom_font_path` (may be null/blank). */
        val legacyPath: String?,
        /** Slot key -> font enum name (e.g. `"date"` -> `"Custom"`). */
        val slotFonts: Map<String, String>
    )

    data class Result(
        val customFontPaths: Set<String>,
        val pathMap: Map<String, String>
    )

    /** Returns null when nothing needed changing. */
    fun repair(input: Input): Result? {
        val available = availableFonts(input.filesDir)
        val filesRoot = input.filesDir.absolutePath.trimEnd(File.separatorChar) + File.separator

        fun resolve(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val file = File(raw)
            if (file.isFile) {
                // A path inside filesDir is restored with the archive itself; prefer it even if
                // the stored path was written by another install/package.
                if (file.absolutePath.startsWith(filesRoot)) return file.absolutePath
                available[file.name]?.let { return it.absolutePath }
                return file.absolutePath
            }
            // Relative paths are resolved against filesDir (some old backups used them).
            if (!file.isAbsolute) {
                val relative = File(input.filesDir, raw)
                if (relative.isFile) return relative.absolutePath
            }
            return available[file.name]?.absolutePath
        }

        val resolvedPaths = LinkedHashSet<String>()
        input.customFontPaths.forEach { path -> resolve(path)?.let(resolvedPaths::add) }

        val resolvedMap = LinkedHashMap<String, String>()
        input.pathMap.forEach { (slot, path) ->
            resolve(path)?.let { resolved ->
                resolvedMap[slot] = resolved
                resolvedPaths.add(resolved)
            }
        }
        val legacyResolved = resolve(input.legacyPath)
        if (legacyResolved != null) resolvedPaths.add(legacyResolved)

        val missingCustomSlots = input.slotFonts
            .filter { (slot, font) -> font == "Custom" && resolvedMap[slot].isNullOrBlank() }
            .keys
        if (missingCustomSlots.isNotEmpty()) {
            // Only reuse a font that is actually referenced somewhere, otherwise a single
            // font left in filesDir is the only possible intent.
            val fallback = resolvedMap["universal"]
                ?: legacyResolved
                ?: resolvedPaths.singleOrNull()
                ?: available.values.singleOrNull()?.absolutePath
            if (fallback != null) {
                missingCustomSlots.forEach { slot ->
                    resolvedMap[slot] = fallback
                    resolvedPaths.add(fallback)
                }
            }
        }

        if (resolvedMap == input.pathMap && resolvedPaths == input.customFontPaths) return null
        return Result(resolvedPaths, resolvedMap)
    }

    /** Filename -> font file, limited to two levels under filesDir (fonts are imported there). */
    private fun availableFonts(dir: File): Map<String, File> {
        if (!dir.isDirectory) return emptyMap()
        val out = LinkedHashMap<String, File>()
        dir.walkTopDown().maxDepth(2).forEach { file ->
            if (file.isFile && file.extension.lowercase() in FONT_EXTENSIONS) {
                out.putIfAbsent(file.name, file)
            }
        }
        return out
    }
}
