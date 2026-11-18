package com.github.gezimos.inkos.helper

/**
 * 厂商墨水屏增强能力声明：系统全刷 / 翻页动画 / 智能刷新模式。
 *
 * 开关与引导是否出现，只看这里的能力位，**不在 UI 里直接判断某一家的实现类**。
 * 目前只有大我后端（[BigmeEink]）；接新品牌时：实现该品牌的后端，并在本对象里
 * 合并它的能力位即可，界面代码不用再改。
 *
 * 说明：偏好键仍是 `EINK_*`（描述「墨水屏增强」，与品牌无关），换品牌继续复用。
 */
object EinkCapabilities {

    /** 厂商全刷（GC16 整屏 clear）。 */
    val supportsSystemFullRefresh: Boolean
        get() = BigmeEink.isAvailable

    /** 翻页动画（方向擦除 / Ripple 样式）。 */
    val supportsPageTurnAnimation: Boolean
        get() = BigmeEink.isAvailable

    /** 智能刷新模式（按交互在 Regal 与极速波形之间切换）。 */
    val supportsSmartRefreshMode: Boolean
        get() = BigmeEink.isAvailable

    /** 任意一项可用：用来决定是否插入「厂商增强」引导页。 */
    val anySupported: Boolean
        get() = supportsSystemFullRefresh || supportsPageTurnAnimation || supportsSmartRefreshMode
}
