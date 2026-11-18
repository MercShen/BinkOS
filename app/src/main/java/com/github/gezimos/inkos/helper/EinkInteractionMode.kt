package com.github.gezimos.inkos.helper

import android.content.Context
import android.util.Log
import com.github.gezimos.inkos.data.Prefs

/**
 * 交互界面（编辑模式 / 短滑区域编辑器 / 底部面板）临时切「极速」刷新波形。
 *
 * 这些界面要拖动、滚动、来回改数值，Regal 的清晰度换不来跟手；退出后回到 Regal，
 * 翻页动画才能继续可见。开关在 Settings → Extras，默认关闭。
 *
 * 用 token 集合而不是计数器：同一界面重复 begin、close 被调用两次（例如退出编辑模式
 * 时连带收掉短滑编辑器）都不会把状态弄歪。
 */
object EinkInteractionMode {

    private const val TAG = "EinkInteractionMode"
    private val activeTokens = mutableSetOf<String>()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun begin(token: String) {
        val ctx = appContext ?: return
        if (!enabled(ctx)) return
        val first = synchronized(activeTokens) { activeTokens.add(token) && activeTokens.size == 1 }
        if (!first) return
        Log.i(TAG, "enter fast mode token=$token")
        BigmeEink.setPackageRefreshMode(ctx, BigmeEink.MODE_SUPER)
    }

    fun end(token: String) {
        val ctx = appContext ?: return
        val last = synchronized(activeTokens) { activeTokens.remove(token) && activeTokens.isEmpty() }
        if (!last) return
        Log.i(TAG, "leave fast mode token=$token -> Regal")
        BigmeEink.setPackageRefreshMode(ctx, BigmeEink.MODE_REGAL)
    }

    /** Activity 销毁或开关被关掉时的兜底：清空 token 并回到 Regal。 */
    fun reset() {
        val ctx = appContext ?: return
        val had = synchronized(activeTokens) {
            val active = activeTokens.isNotEmpty()
            activeTokens.clear()
            active
        }
        if (!had) return
        Log.i(TAG, "reset fast mode -> Regal")
        BigmeEink.setPackageRefreshMode(ctx, BigmeEink.MODE_REGAL)
    }

    private fun enabled(context: Context): Boolean = try {
        Prefs(context).einkInteractionFastMode
    } catch (_: Throwable) {
        false
    }
}
