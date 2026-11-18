package com.github.gezimos.inkos.helper

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 应用内语言（per-app language）。
 *
 * 为什么 API 33+ 不再走 AppCompatDelegate / LocaleManager：
 * 平台写完 per-app 配置后要重启该 App 的界面，而本 App 是默认桌面（HOME）。在
 * android-14 的实现里这次重启会走到
 * PackageConfigurationUpdaterImpl.updateConfig() →
 * ConfigurationContainer.applyAppSpecificConfig()，把 home 的窗口类型合并进已经记成
 * standard 的活动配置，抛
 *   IllegalStateException: Can't change activity type once set ... activityType=home
 * 异常经 Binder 回抛到应用进程，切语言必崩；而且持久化在 updateConfig 之后，
 * 语言其实也没存进去。这里改为自己存 tag、自己在 attachBaseContext 套 locale，
 * 完全绕开系统的应用重启。
 *
 * API 32 及以下仍交给 AppCompat（它的本地实现不经过 LocaleManager，是安全的）。
 */
object AppLocale {

    private const val PREFS_NAME = "app_locale"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    /** 33+ 的系统实现会崩，改为自管；更低版本交给 AppCompat。 */
    private fun selfManaged(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 当前语言 tag；空串表示跟随系统。 */
    fun currentTag(context: Context): String =
        if (selfManaged()) storedTag(context) else appCompatTag()

    /**
     * 切换语言。自管模式下只写自己的偏好，界面重建由调用方负责
     * （`activity.recreate()`），不会触发平台把桌面当 home 任务重启。
     */
    fun setTag(context: Context, tag: String) {
        if (selfManaged()) {
            val ctx = context.applicationContext ?: context
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE_TAG, tag)
                .apply()
        } else {
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tag)
            )
        }
    }

    /**
     * 在 attachBaseContext 里套语言。跟随系统时原样返回，但会把进程默认 Locale
     * 还原成系统语言，避免同一次进程里切回「系统」后仍残留上一次的选择。
     */
    fun wrapContext(base: Context): Context {
        val tag = if (selfManaged()) storedTag(base) else ""
        val locale = if (tag.isEmpty()) null else Locale.forLanguageTag(tag)
        if (locale == null || locale.language.isNullOrEmpty()) {
            val systemLocales = base.resources.configuration.locales
            if (systemLocales.isEmpty) return base
            Locale.setDefault(systemLocales[0])
            return base
        }
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        Locale.setDefault(locale)
        return base.createConfigurationContext(config)
    }

    private fun storedTag(context: Context): String {
        val ctx = context.applicationContext ?: context
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_LANGUAGE_TAG, null)
        if (stored != null) return stored
        // 从走平台 LocaleManager 的旧版本升级上来：把已有选择搬进自己的偏好
        // （只读系统值，不触发任何写入）。
        val legacy = appCompatTag()
        prefs.edit().putString(KEY_LANGUAGE_TAG, legacy).apply()
        return legacy
    }

    private fun appCompatTag(): String = try {
        AppCompatDelegate.getApplicationLocales().toLanguageTags()
    } catch (_: Exception) {
        ""
    }
}
