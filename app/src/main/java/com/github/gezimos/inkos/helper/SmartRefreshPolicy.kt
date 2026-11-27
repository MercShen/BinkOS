package com.github.gezimos.inkos.helper

/**
 * 智能刷新模式的波形归属决策——纯函数，JVM 里可测，不碰反射也不碰 Context。
 *
 * 规范（2026-11-27 与 Merc 定）：
 *  - **开启**：记住当时本包的刷新波形，并把基线设为 Regal（已经是 Regal/HD256 就不重复写，
 *    避免把「没变化」写成一次真实的策略 apply，那在观感上是一次全刷）。
 *  - **交互中**：拖动/编辑时切极速，由 [EinkInteractionMode] 负责。
 *  - **关闭**：把波形还给开启前那个值。但若用户在开启期间自己去厂商 e-ink center 改过，
 *    **不覆盖他的改动**，只清记忆。
 *  - **读不到原波形**：什么都不改。宁可这个开关不生效，也不能留下「改了但不知道怎么还原」
 *    的不可逆状态。
 *
 * Regal 的归属权在智能刷新开关上（不在翻页动画上）。因此关掉智能刷新后，如果翻页动画
 * 还开着，擦除动画会看不见——非 Regal/HD256 不渲染动画是厂商限制。这条由首次开启时的
 * 引导弹窗承担说明责任，不在此重复提醒。
 */
object SmartRefreshPolicy {

    /** 「没记住」的哨兵值。存进 Prefs 用同一个常量，别用 0（0 是合法波形编号）。 */
    const val UNKNOWN = -1

    /**
     * 开启时的决定。
     * @param current [BigmeEink.readPackageRefreshMode] 的返回值，null = 读不到
     * @return null 表示**拒绝开启**；否则给出要记住的原值与要不要写入的基线
     */
    data class On(val remember: Int, val writeMode: Int?)

    fun on(current: Int?): On? {
        val c = current ?: return null
        // 已经能渲染动画：记住原值，但不写——省掉一次无意义的策略 apply（观感是一次全刷）
        val write = if (BigmeEink.isAnimationCapable(c)) null else BigmeEink.MODE_REGAL
        return On(remember = c, writeMode = write)
    }

    /**
     * 开启期间「应有的」波形：我们写了就是写进去的值，没写就是原值本身。
     * 关闭时用它判断「用户有没有自己动过」。
     */
    fun baselineOf(decision: On): Int = decision.writeMode ?: decision.remember

    /**
     * 关闭时该写回什么。
     * @param before   开启时记住的原值（[UNKNOWN] = 没记住）
     * @param baseline 开启期间应有的波形（[baselineOf]）
     * @param current  关闭时读到的当前波形，null = 读不到
     * @param interactionMode 交互期间我们自己写进去的波形（极速）
     * @return null 表示**不要动**厂商策略
     *
     * 「当前值不等于基线」通常意味着用户在厂商菜单里自己改过，不能覆盖。但**交互进行中**
     * 当前值就是我们自己写的极速（例如在编辑模式里直接关开关），那是我们动的手，必须还原。
     */
    fun restore(before: Int, baseline: Int, current: Int?, interactionMode: Int? = null): Int? {
        if (before == UNKNOWN) return null
        // 读不到当前值就保守不动：此刻它可能已经被用户改过，写回会覆盖他的选择
        val now = current ?: return null
        if (now != baseline && now != interactionMode) return null
        return before
    }

    /**
     * 拖动结束后该回到哪个波形：只在智能刷新开着时写，否则一律不动。
     *
     * 开着但**没有记录**（从旧版本升级上来、当时还没有记忆机制）时兜底回 Regal：
     * 开关开着就意味着「本功能的基线是 Regal」，这是它自己的契约，不是用户开启前的模式，
     * 所以写它是安全的。不兜底的后果更糟——交互结束拿不到基线，本包会一直停在极速(179)，
     * 比改动前的行为更差。
     */
    fun interactionBaseline(smartRefreshOn: Boolean, before: Int, baseline: Int): Int? {
        if (!smartRefreshOn) return null
        if (baseline != UNKNOWN) return baseline
        if (before != UNKNOWN) return before
        return BigmeEink.MODE_REGAL
    }
}
