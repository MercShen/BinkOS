package com.github.gezimos.inkos.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hub 短滑判定：横/竖共用一套「总位移主导轴」规则。
 *
 * 数值按 2.5x 密度折算：48dp = 120px、32dp = 80px、350dp/s = 875px/s。
 */
class AxisSwipeGestureTest {

    private val horizontalThreshold = 120f
    private val verticalThreshold = 120f
    private val flickMin = 80f
    private val flickVelocity = 875f

    private fun resolve(
        dx: Float,
        dy: Float,
        elapsedMs: Long = 200L
    ): SwipeDirection? = resolveSwipeDirection(
        dx = dx,
        dy = dy,
        elapsedMs = elapsedMs,
        horizontalThresholdPx = horizontalThreshold,
        verticalThresholdPx = verticalThreshold,
        flickMinPx = flickMin,
        flickVelocityPxPerSec = flickVelocity
    )

    @Test
    fun diagonalSwipeWithVerticalDriftStillSwitchesTab() {
        // 回归场景：两轴都远超 slop、竖向还先越线，但只要总位移横向占优就必须切 tab。
        assertEquals(SwipeDirection.Left, resolve(dx = -260f, dy = 200f))
        assertEquals(SwipeDirection.Right, resolve(dx = 260f, dy = -200f))
    }

    @Test
    fun verticalDominantSwipePages() {
        assertEquals(SwipeDirection.Up, resolve(dx = -40f, dy = -260f))
        assertEquals(SwipeDirection.Down, resolve(dx = 40f, dy = 260f))
    }

    @Test
    fun equalAxesFallBackToVertical() {
        // 正 45° 无法判断意图时不做横切，避免误切分类。
        assertEquals(SwipeDirection.Down, resolve(dx = 200f, dy = 200f))
        assertEquals(SwipeDirection.Up, resolve(dx = -200f, dy = -200f))
    }

    @Test
    fun shortFastFlickSwitchesTab() {
        // 100px < 120px 阈值，但 100ms 内划完 = 1000px/s，走快滑兜底。
        assertEquals(SwipeDirection.Left, resolve(dx = -100f, dy = 6f, elapsedMs = 100L))
    }

    @Test
    fun shortSlowDragDoesNothing() {
        // 同样是 100px，慢慢拖 800ms = 125px/s，不算短滑。
        assertNull(resolve(dx = -100f, dy = 6f, elapsedMs = 800L))
    }

    @Test
    fun flickFallbackNeverAppliesToVertical() {
        // 竖向保持 48dp：快而短的竖向手势不翻页（原来是同样的 48dp 行为）。
        assertNull(resolve(dx = 6f, dy = -100f, elapsedMs = 50L))
    }

    @Test
    fun flickBelowMinimumDistanceDoesNothing() {
        assertNull(resolve(dx = -70f, dy = 0f, elapsedMs = 30L))
    }

    @Test
    fun distanceThresholdMetAtLowSpeedStillWorks() {
        // 越过阈值时不需要速度：慢拖 130px 也切 tab。
        assertEquals(SwipeDirection.Left, resolve(dx = -130f, dy = 10f, elapsedMs = 900L))
    }

    @Test
    fun zeroElapsedTimeIsSafe() {
        assertEquals(SwipeDirection.Left, resolve(dx = -100f, dy = 0f, elapsedMs = 0L))
    }

    @Test
    fun noMovementDoesNothing() {
        assertNull(resolve(dx = 0f, dy = 0f))
    }

    @Test
    fun belowThresholdDoesNothing() {
        assertNull(resolve(dx = -40f, dy = 30f))
        assertNull(resolve(dx = 10f, dy = -40f))
    }
}
