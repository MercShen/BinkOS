package com.github.gezimos.inkos.helper

import android.content.Context
import android.graphics.Typeface
import android.util.LruCache
import java.io.File

/**
 * 有界字体文件缓存：key = path + length + lastModified。
 * - 不构造 Prefs；需要默认自定义字体路径时直接读 SharedPreferences
 * - 命中缓存不碰文件内容；未命中才 Typeface.createFromFile（首次/变更需要 file IO）
 * - 可在后台预热（类里不保证首次加载全异步，调用方自行决定）
 */
object FontFileCache {
    private const val PREFS_FILENAME = "com.github.gezimos.inkos"
    private const val KEY_CUSTOM_FONT_PATH = "custom_font_path"
    private const val MAX_ENTRIES = 8

    private val cache = object : LruCache<String, Typeface>(MAX_ENTRIES) {}
    private val loaderLock = Any()
    private val prefetchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "inkos-font-prefetch").apply { isDaemon = true }
    }

    /** 纯函数，便于单测：path + 文件元数据决定缓存键。 */
    fun keyFor(path: String, length: Long, lastModified: Long): String = "$path|$length|$lastModified"

    fun defaultCustomFontPath(context: Context): String? = try {
        context.getSharedPreferences(PREFS_FILENAME, 0).getString(KEY_CUSTOM_FONT_PATH, null)
    } catch (_: Exception) {
        null
    }

    fun get(context: Context, path: String): Typeface? {
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        val key = keyFor(path, file.length(), file.lastModified())
        cache.get(key)?.let { return it }
        return synchronized(loaderLock) {
            cache.get(key) ?: try {
                Typeface.createFromFile(file).also { cache.put(key, it) }
            } catch (_: Exception) { null }
        }
    }

    /** 后台预热一次（单线程，合并重复路径）；失败静默。 */
    fun prefetch(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        val appContext = context.applicationContext
        prefetchExecutor.execute {
            try { get(appContext, path) } catch (_: Exception) {}
        }
    }

    fun clear() = cache.evictAll()
}
