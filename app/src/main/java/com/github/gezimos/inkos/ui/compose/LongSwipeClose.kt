package com.github.gezimos.inkos.ui.compose

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.github.gezimos.inkos.data.Constants
import com.github.gezimos.inkos.data.Prefs
import com.github.gezimos.inkos.helper.BigmeEink
import com.github.gezimos.inkos.helper.GestureBindings
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 功能界面「反向长滑关闭」。
 *
 * 在 **Initial pass** 上监听手势：子级的 pager / 滚动容器拿不到“先手”，
 * 所以不会再出现“内层滚动把长滑吃掉”的情况。
 *
 * 规则：
 * - 只有该界面**被显式绑定**了打开方向，且手势是与之相反的**长滑**
 *   （距离 ≥ 100dp × longSwipeRatio，且有足够速度）才触发；
 * - 没有绑定打开方向的界面（例如从图标/返回栈进入的设置页）不会反向长滑退出；
 * - 非反向、非长滑、点击、短滑：完全不消费事件，原有翻页/滚动行为不变；
 * - 判定成立后立即 consume 后续事件，避免同一次手势又被内层翻页响应。
 */
@Composable
fun Modifier.closeOnReverseLongSwipe(
    action: Constants.Action,
    prefs: Prefs,
    enabled: Boolean = true,
    onClose: () -> Unit
): Modifier {
    val density = LocalDensity.current
    val longRatio = prefs.longSwipeThresholdRatio
    val closeDirection = remember(
        action,
        prefs.swipeLeftAction,
        prefs.swipeRightAction,
        prefs.swipeUpAction,
        prefs.swipeDownAction
    ) {
        GestureBindings.closeDirectionOrNull(action, prefs)
    }
    val minDistance = with(density) { (100.dp * longRatio).toPx() }
    val currentOnClose = rememberUpdatedState(onClose)
    // closeDirection 依赖「最近一次滑动的方向」（GestureBindings 内部读 BigmeEink.recentSwipeOrNull），
    // 是会变的值。把它当 pointerInput 的 key，方向一变探测器就在**手势中途**被销毁重建，
    // 前半段位移直接丢；锁屏解锁后方向过期时尤其容易踩到。改成每次手势开始时现读。
    val currentCloseDirection = rememberUpdatedState(closeDirection)
    // 闸门必须只看偏好、不看最近手势：`closeDirectionOrNull` 为 null 时既可能是「没绑定」，
    // 也可能是「绑定了但这一刻取不到方向」。2026-11-28 就是把它挪进 awaitEachGesture 里当
    // 早退条件，结果**未绑定的界面也开始跑指针循环**，而 `return@awaitEachGesture` 并不会
    // 终止 awaitEachGesture —— 它会对同一个活动手势立刻重跑这个块，实测主线程 92% 单核
    // 自转 90 秒以上不退出（模拟器与 B651 均复现，栈顶 LongSwipeClose.kt:62）。
    // 契约在 GestureBindings.closeDirectionOrNull 的注释里写明：未绑定就不得启用退出探测。
    val boundToSomeSwipe = remember(
        action,
        prefs.swipeLeftAction,
        prefs.swipeRightAction,
        prefs.swipeUpAction,
        prefs.swipeDownAction
    ) { GestureBindings.isBoundToAnySwipe(action, prefs) }

    return this.pointerInput(enabled, minDistance, boundToSomeSwipe) {
        if (!enabled) return@pointerInput
        // 未绑定：连探测器都不启动。这是修复自转的关键一行。
        if (!boundToSomeSwipe) return@pointerInput
        awaitEachGesture {
            // 方向每轮现读；这一刻取不到就只是本轮不判定，循环照常走到抬手为止。
            val targetDirection = currentCloseDirection.value
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = down.position
            val startTime = SystemClock.elapsedRealtime()
            var pressed = true
            var closing = false

            while (pressed) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dx = change.position.x - start.x
                val dy = change.position.y - start.y
                val dist = hypot(dx, dy)

                if (!closing && dist >= minDistance) {
                    val elapsedMs = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1L)
                    val velocity = dist / (elapsedMs / 1000f) // px/s
                    val direction = when {
                        abs(dx) > abs(dy) ->
                            if (dx > 0) BigmeEink.Direction.L2R else BigmeEink.Direction.R2L
                        else ->
                            if (dy > 0) BigmeEink.Direction.T2B else BigmeEink.Direction.B2T
                    }
                    if (direction == targetDirection && velocity >= 150f) {
                        closing = true
                    }
                }

                if (closing) {
                    change.consume()
                }
                pressed = change.pressed
            }

            if (closing) {
                try { currentOnClose.value() } catch (_: Exception) {}
            }
        }
    }
}
