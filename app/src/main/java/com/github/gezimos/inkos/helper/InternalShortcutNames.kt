package com.github.gezimos.inkos.helper

import android.content.Context
import com.github.gezimos.inkos.R
import com.github.gezimos.inkos.data.Constants

/**
 * 内部快捷方式（设置/抽屉/通知/托盘/中枢/最近任务）的显示名。
 *
 * 系统的 ShortcutManager 解析静态快捷方式的 label 时用的是**系统语言**，
 * 不会跟随应用内语言设置；所以统一在渲染/构建列表时用资源重新取一次。
 * 资源解析走当前 Context（Activity 的 locale），因此语言切换后立即生效。
 */
object InternalShortcutNames {

    fun resolve(context: Context, packageName: String, shortcutId: String?): String? {
        val internalIds = setOf(
            Constants.INKOS_SHORTCUT_APP_DRAWER,
            Constants.INKOS_SHORTCUT_NOTIFICATIONS,
            Constants.INKOS_SHORTCUT_SIMPLE_TRAY,
            Constants.INKOS_SHORTCUT_HUB,
            Constants.INKOS_SHORTCUT_RECENTS,
            Constants.INKOS_SHORTCUT_SETTINGS
        )
        val id = when {
            packageName == context.packageName -> shortcutId
            packageName in internalIds -> packageName
            else -> null
        } ?: return null

        return try {
            when (id) {
                Constants.INKOS_SHORTCUT_APP_DRAWER -> context.getString(R.string.app_drawer)
                Constants.INKOS_SHORTCUT_NOTIFICATIONS -> context.getString(R.string.shortcut_letters_short)
                Constants.INKOS_SHORTCUT_SIMPLE_TRAY -> context.getString(R.string.shortcut_simple_tray_short)
                Constants.INKOS_SHORTCUT_HUB -> context.getString(R.string.shortcut_hub_short)
                Constants.INKOS_SHORTCUT_RECENTS -> context.getString(R.string.shortcut_recents_short)
                Constants.INKOS_SHORTCUT_SETTINGS -> context.getString(R.string.inkos_settings)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
