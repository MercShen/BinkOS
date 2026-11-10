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
        val recent = BigmeEink.recentSwipeOrNull()
        if (recent != null && actionFor(recent, prefs) == action) return recent
        return DIRECTIONS.firstOrNull { actionFor(it, prefs) == action }
    }

    fun opposite(direction: BigmeEink.Direction): BigmeEink.Direction = when (direction) {
        BigmeEink.Direction.L2R -> BigmeEink.Direction.R2L
        BigmeEink.Direction.R2L -> BigmeEink.Direction.L2R
        BigmeEink.Direction.T2B -> BigmeEink.Direction.B2T
        BigmeEink.Direction.B2T -> BigmeEink.Direction.T2B
    }

    /**
     * 功能未绑定任何方向时的默认「打开方向」。
     *
     * 通知类界面（letters / simple tray / hub）历史上都是「向上滑关闭」，
     * 所以默认打开方向取向下滑；抽屉与最近任务历史上是「向下长滑关闭」，取向上。
     */
    fun defaultOpenDirection(action: Constants.Action): BigmeEink.Direction = when (action) {
        Constants.Action.OpenLettersScreen,
        Constants.Action.OpenSimpleTray,
        Constants.Action.OpenHub,
        -> BigmeEink.Direction.T2B
        else -> BigmeEink.Direction.B2T
    }

    /** 打开该界面时的动画方向：绑定方向 → 最近手势 → 默认。 */
    fun openDirection(action: Constants.Action, prefs: Prefs): BigmeEink.Direction =
        directionFor(action, prefs) ?: BigmeEink.recentSwipeOrNull() ?: defaultOpenDirection(action)

    /** 关闭该界面时的动画方向 = 打开方向的相反方向，也就是关闭手势的方向。 */
    fun closeDirection(action: Constants.Action, prefs: Prefs): BigmeEink.Direction =
        opposite(openDirection(action, prefs))
}
