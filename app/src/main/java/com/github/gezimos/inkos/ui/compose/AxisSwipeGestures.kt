package com.github.gezimos.inkos.ui.compose

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** 一次滑动最终落在哪个方向。 */
internal enum class SwipeDirection { Left, Right, Up, Down }

/**
 * 按**总位移**决定主导轴，而不是「谁的 touch slop 先到」。
 *
 * 背景：Hub 原来把横向挂在根 Box、竖向挂在内层 Column。Compose 的 Main pass 是内层先收事件，
 * 一次 move 里 dy 也越过 slop 时，内层竖向探测器先 `consume()`，横向探测器直接判废——
 * 斜一点的左/右短滑因此常被当成上/下翻页（末页时表现为「划了没反应」）。
 * 墨水屏触控一次短滑只有 2–4 个 move 事件，两条轴经常在同一事件里同时越线，所以这个冲突很常见。
 *
 * 现在两条轴只在松手时用总位移比大小，横/竖不会再互相抢事件。
 *
 * @param dx 横向总位移（px，向右为正）
 * @param dy 纵向总位移（px，向下为正）
 * @param elapsedMs 从按下到抬起的时长，用于快滑兜底
 * @param horizontalThresholdPx 横向距离阈值（默认 48dp）
 * @param verticalThresholdPx 纵向距离阈值（默认 48dp）
 * @param flickMinPx 横向快滑兜底的最小距离（默认 32dp）
 * @param flickVelocityPxPerSec 横向快滑兜底要求的速度（默认 350dp/s 折算成 px/s）
 */
internal fun resolveSwipeDirection(
    dx: Float,
    dy: Float,
    elapsedMs: Long,
    horizontalThresholdPx: Float,
    verticalThresholdPx: Float,
    flickMinPx: Float,
    flickVelocityPxPerSec: Float
): SwipeDirection? {
    val absDx = abs(dx)
    val absDy = abs(dy)
    val horizontalDominant = absDx > absDy
    val distance = if (horizontalDominant) absDx else absDy
    val threshold = if (horizontalDominant) horizontalThresholdPx else verticalThresholdPx

    val flickAllows = horizontalDominant &&
        flickMinPx > 0f &&
        flickVelocityPxPerSec > 0f &&
        distance >= flickMinPx &&
        distance / ((elapsedMs.coerceAtLeast(1L)) / 1000f) >= flickVelocityPxPerSec

    if (distance < threshold && !flickAllows) return null

    return if (horizontalDominant) {
        if (dx < 0f) SwipeDirection.Left else SwipeDirection.Right
    } else {
        if (dy < 0f) SwipeDirection.Up else SwipeDirection.Down
    }
}

/**
 * 水平 + 垂直短滑共用一个探测器：手势开始时不抢事件，越过一点起步距离后才消费，
 * 松手时交给 [resolveSwipeDirection] 决定方向。
 *
 * 不放行「行内点击 / 长按」：没越过起步距离的手势不消费任何事件，子级点击照常工作；
 * 一旦开始拖动就消费，子级 tap 在 Final pass 看到消费后自己取消。
 *
 * 注意：`pointerInput` 的 key 只用阈值（稳定值），不要把页码/选中 tab 这类易变状态放进来，
 * 否则每次翻页/切 tab 都会重建探测器，连续两次快滑的第二下可能被取消。
 */
@Composable
fun Modifier.axisSwipeGestures(
    horizontalThreshold: Dp = 48.dp,
    verticalThreshold: Dp = 48.dp,
    flickMinDistance: Dp = 32.dp,
    flickVelocity: Dp = 350.dp,
    onDragStarted: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null
): Modifier {
    val density = LocalDensity.current
    val horizontalThresholdPx = with(density) { horizontalThreshold.toPx() }
    val verticalThresholdPx = with(density) { verticalThreshold.toPx() }
    val flickMinPx = with(density) { flickMinDistance.toPx() }
    val flickVelocityPxPerSec = with(density) { flickVelocity.toPx() }
    val startSlopPx = with(density) { 16.dp.toPx() }

    val currentOnDragStarted = rememberUpdatedState(onDragStarted)
    val currentOnSwipeLeft = rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight = rememberUpdatedState(onSwipeRight)
    val currentOnSwipeUp = rememberUpdatedState(onSwipeUp)
    val currentOnSwipeDown = rememberUpdatedState(onSwipeDown)

    return this.pointerInput(
        horizontalThresholdPx,
        verticalThresholdPx,
        flickMinPx,
        flickVelocityPxPerSec
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            val startX = down.position.x
            val startY = down.position.y
            val startTime = SystemClock.uptimeMillis()
            var dragged = false
            var canceled = false
            var lastX = startX
            var lastY = startY

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.count { it.pressed } > 1) {
                    // 双指手势（缩放等）不在本判定范围内，留给系统。
                    canceled = true
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) {
                    // 别的消费者（反向长滑关闭等）接管了这次手势。
                    canceled = true
                    break
                }
                lastX = change.position.x
                lastY = change.position.y
                if (!dragged && (abs(lastX - startX) > startSlopPx || abs(lastY - startY) > startSlopPx)) {
                    dragged = true
                    try { currentOnDragStarted.value?.invoke() } catch (_: Exception) {}
                }
                if (dragged) change.consume()
                if (!change.pressed) break
            }

            if (!canceled && dragged) {
                val direction = resolveSwipeDirection(
                    dx = lastX - startX,
                    dy = lastY - startY,
                    elapsedMs = SystemClock.uptimeMillis() - startTime,
                    horizontalThresholdPx = horizontalThresholdPx,
                    verticalThresholdPx = verticalThresholdPx,
                    flickMinPx = flickMinPx,
                    flickVelocityPxPerSec = flickVelocityPxPerSec
                )
                when (direction) {
                    SwipeDirection.Left -> currentOnSwipeLeft.value?.invoke()
                    SwipeDirection.Right -> currentOnSwipeRight.value?.invoke()
                    SwipeDirection.Up -> currentOnSwipeUp.value?.invoke()
                    SwipeDirection.Down -> currentOnSwipeDown.value?.invoke()
                    null -> Unit
                }
            }
        }
    }
}
