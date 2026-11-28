package com.github.gezimos.inkos.helper

import com.github.gezimos.inkos.data.Constants
import com.github.gezimos.inkos.data.Prefs

/**
 * 首页「长滑手势 → 功能」绑定关系，供大我动画与按方向关闭界面共用。
 *
 * 方向是**手指移动方向**：L2R = 手指向右滑（即首页设置里的「向右滑」，swipeRightAction）。
 */
object GestureBindings {

    private val DIRECTIONS = arrayOf(
        BigmeEink.Direction.L2R,
        BigmeEink.Direction.R2L,
        BigmeEink.Direction.T2B,
        BigmeEink.Direction.B2T,
    )

    /** 某个手指方向当前绑定的功能（对应首页四个滑动设置）。 */
    fun actionFor(direction: BigmeEink.Direction, prefs: Prefs): Constants.Action = when (direction) {
        BigmeEink.Direction.L2R -> prefs.swipeRightAction // 手指向右
        BigmeEink.Direction.R2L -> prefs.swipeLeftAction  // 手指向左
        BigmeEink.Direction.T2B -> prefs.swipeDownAction  // 手指向下
        BigmeEink.Direction.B2T -> prefs.swipeUpAction    // 手指向上
    }

    /** 这个功能被绑定在哪个方向；多个方向绑定时优先最近触发的那个。 */
    fun directionFor(action: Constants.Action, prefs: Prefs): BigmeEink.Direction? {
        val actions = DIRECTIONS.associateWith { actionFor(it, prefs) }
        return boundDirection(action, actions, BigmeEink.recentSwipeOrNull())
    }

    /**
     * 纯逻辑版本：该功能被绑定在哪个方向。
     * 只有 action 真的被某个方向映射到才返回非 null；不做任何默认方向回退。
     */
    internal fun boundDirection(
        action: Constants.Action,
        actions: Map<BigmeEink.Direction, Constants.Action>,
        recent: BigmeEink.Direction?
    ): BigmeEink.Direction? {
        if (recent != null && actions[recent] == action) return recent
        return DIRECTIONS.firstOrNull { actions[it] == action }
    }

    /**
     * 纯逻辑版本：反向长滑关闭方向。
     * 未绑定打开方向时返回 null，调用方不得启用反向长滑退出。
     */
    internal fun closeDirectionFor(
        action: Constants.Action,
        actions: Map<BigmeEink.Direction, Constants.Action>,
        recent: BigmeEink.Direction?
    ): BigmeEink.Direction? =
        boundDirection(action, actions, recent)?.let(::opposite)

    fun opposite(direction: BigmeEink.Direction): BigmeEink.Direction = when (direction) {
        BigmeEink.Direction.L2R -> BigmeEink.Direction.R2L
        BigmeEink.Direction.R2L -> BigmeEink.Direction.L2R
        BigmeEink.Direction.T2B -> BigmeEink.Direction.B2T
        BigmeEink.Direction.B2T -> BigmeEink.Direction.T2B
    }

    /**
     * 该功能是否被用户绑定在**任意**滑动方向上。
     *
     * 只看偏好，不看「最近滑动方向」，所以它的值只在用户改绑定时变化 ——
     * 正好适合当 `pointerInput` 的 key 和「要不要启动反向长滑探测器」的闸门。
     * `closeDirectionOrNull` 不能当这个闸门：它依赖最近手势，每滑一次都可能变，
     * 而且它是 null 时并不代表「没绑定」（见 [isBoundToAnySwipe] 的纯逻辑版）。
     */
    fun isBoundToAnySwipe(action: Constants.Action, prefs: Prefs): Boolean =
        isBoundToAnySwipe(action, DIRECTIONS.associateWith { actionFor(it, prefs) })

    internal fun isBoundToAnySwipe(
        action: Constants.Action,
        actions: Map<BigmeEink.Direction, Constants.Action>
    ): Boolean = DIRECTIONS.any { actions[it] == action }

    /**
     * 反向长滑关闭方向；**只有该功能被用户显式绑定到某个滑动方向时才返回**。
     *
     * 返回 null 表示这个界面没有“打开方向”，因此也不存在“反向”，
     * 调用方（closeOnReverseLongSwipe）必须不启用长滑退出。
     */
    fun closeDirectionOrNull(action: Constants.Action, prefs: Prefs): BigmeEink.Direction? {
        val actions = DIRECTIONS.associateWith { actionFor(it, prefs) }
        return closeDirectionFor(action, actions, BigmeEink.recentSwipeOrNull())
    }
}
