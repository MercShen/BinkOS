package com.github.gezimos.inkos.helper

import android.content.Context
import com.github.gezimos.inkos.BuildConfig
import com.github.gezimos.inkos.data.Prefs

/**
 * 厂商墨水屏增强能力声明：系统全刷 / 翻页动画 / 智能刷新模式。
 *
 * 开关与引导是否出现，只看这里的能力位，**不在 UI 里直接判断某一家的实现类**。
 * 目前只有大我后端（[BigmeEink]）；接新品牌时：实现该品牌的后端，并在本对象里
 * 合并它的能力位即可，界面代码不用再改。
 *
 * 说明：偏好键仍是 `EINK_*`（描述「墨水屏增强」，与品牌无关），换品牌继续复用。
 *
 * 调试后门：模拟器/无厂商固件的机器装 debug 包时，可以用
 * `--es dest vendor_switches_on`（见 docs/automation-entry.md）强制把这些开关全部显示出来，
 * 只影响 UI 可见性，不伪造厂商接口能力；release 构建里恒为 false。
 */
object EinkCapabilities {

    /** 调试后门结果：只有 debug 构建 + 偏好开关都成立时才为 true。 */
    @Volatile
    private var debugForceAll = false

    /** Activity 启动时调用一次：从偏好里恢复后门状态（release 构建恒为 false）。 */
    fun init(context: Context) {
        debugForceAll = BuildConfig.DEBUG && try {
            Prefs(context.applicationContext).debugForceVendorSwitches
        } catch (_: Throwable) {
            false
        }
    }

    /** automation dest `vendor_switches_on/off` 用：同步内存里的后门开关（release 忽略）。 */
    fun setDebugForceAll(enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        debugForceAll = enabled
    }

    /** 厂商全刷（GC16 整屏 clear）。 */
    val supportsSystemFullRefresh: Boolean
        get() = debugForceAll || BigmeEink.isAvailable

    /** 翻页动画（方向擦除 / Ripple 样式）。 */
    val supportsPageTurnAnimation: Boolean
        get() = debugForceAll || BigmeEink.isAvailable

    /** 智能刷新模式（按交互在 Regal 与极速波形之间切换）。 */
    val supportsSmartRefreshMode: Boolean
        get() = debugForceAll || BigmeEink.isAvailable

    /** 任意一项可用：用来决定是否插入「厂商增强」引导页。 */
    val anySupported: Boolean
        get() = supportsSystemFullRefresh || supportsPageTurnAnimation || supportsSmartRefreshMode
}
