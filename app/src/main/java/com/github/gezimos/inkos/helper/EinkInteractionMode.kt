package com.github.gezimos.inkos.helper

import android.content.Context
import android.util.Log
import com.github.gezimos.inkos.data.Prefs

/**
 * 交互界面（编辑模式 / 短滑区域编辑器）临时切「极速」刷新波形。
 *
 * 这些界面要拖动、滚动、来回改数值，Regal 的清晰度换不来跟手；退出后回到 Regal，
 * 翻页动画才能继续可见。开关在 Settings → Extras，默认关闭。
 *
 * 只覆盖「确定在拖动」的两个界面：编辑模式与短滑区域手柄。底部面板不参与 ——
 * 面板多是点击型分页列表，而每次切波形都会让厂商做一次策略 apply（观感上是一次全刷），
 * 打开快捷菜单再进设置这种「开面板→点条目」的流程会白闪一次。
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
        BigmeEink.setPackageRefreshModeAsync(ctx, BigmeEink.MODE_SUPER)
    }

    fun end(token: String) {
        val ctx = appContext ?: return
        val last = synchronized(activeTokens) { activeTokens.remove(token) && activeTokens.isEmpty() }
        if (!last) return
        // 开关已经关了就不该再写：那时关闭流程已经把用户的原波形还回去了，
        // 这里再写一次基线等于把刚还的东西又抢回来。
        restoreBaseline(ctx, "leave fast mode token=$token")
    }

    /** Activity 销毁时的兜底：清空 token 并回到基线（开关还开着才有意义）。 */
    fun reset() {
        val ctx = appContext ?: return
        val had = synchronized(activeTokens) {
            val active = activeTokens.isNotEmpty()
            activeTokens.clear()
            active
        }
        if (!had) return
        restoreBaseline(ctx, "reset")
    }

    /**
     * 关闭智能刷新开关时用：只清 token，**不写任何波形**。
     * 还回原模式由 [MainViewModel.setEinkInteractionFastMode] 负责，避免两处抢着写。
     */
    fun clearTokens() {
        synchronized(activeTokens) { activeTokens.clear() }
    }

    private fun restoreBaseline(context: Context, reason: String) {
        if (!enabled(context)) {
            Log.i(TAG, "$reason: smart refresh off, leaving the vendor mode alone")
            return
        }
        val prefs = try { Prefs(context) } catch (_: Throwable) { return }
        val baseline = com.github.gezimos.inkos.helper.SmartRefreshPolicy.interactionBaseline(
            smartRefreshOn = true,
            before = prefs.smartRefreshModeBefore,
            baseline = prefs.smartRefreshModeBaseline
        )
        if (baseline == null) {
            // 没有记录就不要瞎猜 Regal：宁可停在极速，也不覆盖一个我们不知道的值
            Log.w(TAG, "$reason: no recorded baseline, leaving the vendor mode alone")
            return
        }
        Log.i(TAG, "$reason -> baseline $baseline")
        BigmeEink.setPackageRefreshModeAsync(context, baseline)
    }

    private fun enabled(context: Context): Boolean = try {
        Prefs(context).einkInteractionFastMode
    } catch (_: Throwable) {
        false
    }
}
