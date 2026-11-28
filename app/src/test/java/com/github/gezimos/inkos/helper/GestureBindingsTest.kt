package com.github.gezimos.inkos.helper

import com.github.gezimos.inkos.data.Constants
import org.junit.Assert.*
import org.junit.Test

/**
 * 手势绑定与「反向长滑关闭」探测器的启动条件。
 *
 * 这些规则之所以要有单测：2026-11-28 我把 `closeDirectionOrNull` 的 null 判断从
 * `awaitEachGesture` 外面挪进里面，导致**未绑定**的界面也开始跑指针循环；
 * 而 `return@awaitEachGesture` 并不会终止 `awaitEachGesture`，它会对同一个活动手势
 * 立刻重跑这个块 —— 主线程 92% 单核自转 90 秒以上不退出（模拟器与 B651 均复现）。
 * 下面钉住的就是「未绑定 ⇒ 永远不该有可执行的关闭方向」这条不变式，
 * 它是探测器闸门的依据。
 */
class GestureBindingsTest {

    private val dirs = listOf(
        BigmeEink.Direction.L2R,
        BigmeEink.Direction.R2L,
        BigmeEink.Direction.T2B,
        BigmeEink.Direction.B2T
    )

    private fun mapOf(bindings: Map<BigmeEink.Direction, Constants.Action>) =
        dirs.associateWith { bindings[it] ?: Constants.Action.Disabled }

    @Test fun unboundActionIsNotBoundToAnySwipe() {
        val actions = mapOf(emptyMap())
        assertFalse(GestureBindings.isBoundToAnySwipe(Constants.Action.OpenSettings, actions))
    }

    @Test fun actionBoundToAnyDirectionCountsAsBound() {
        for (d in dirs) {
            val actions = mapOf(d to Constants.Action.OpenSettings)
            assertTrue("绑定在 $d 时应当算已绑定",
                GestureBindings.isBoundToAnySwipe(Constants.Action.OpenSettings, actions))
        }
    }

    @Test fun unboundMeansNoCloseDirectionForEveryRecentSwipe() {
        // 探测器闸门的真正依据：没绑定时，无论「最近滑动方向」是什么，都不该有关闭方向
        val actions = mapOf(emptyMap())
        for (recent in dirs + listOf<BigmeEink.Direction?>(null)) {
            assertNull(
                GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions, recent)
            )
        }
    }

    @Test fun boundActionClosesWithTheOppositeDirection() {
        // 手指向左滑打开设置 ⇒ 反向（向右）长滑关闭
        val actions = mapOf(BigmeEink.Direction.R2L to Constants.Action.OpenSettings)
        assertEquals(
            BigmeEink.Direction.L2R,
            GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions, null)
        )
        // 手指向上滑打开（T2B 是向下）⇒ 反向是向上
        val actions2 = mapOf(BigmeEink.Direction.T2B to Constants.Action.OpenSettings)
        assertEquals(
            BigmeEink.Direction.B2T,
            GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions2, null)
        )
    }

    @Test fun recentSwipeWinsWhenItAlsoOpensTheAction() {
        val actions = mapOf(
            BigmeEink.Direction.L2R to Constants.Action.OpenSettings,
            BigmeEink.Direction.R2L to Constants.Action.OpenSettings
        )
        // 两个方向都能打开设置时，「最近那次滑动」决定反向是哪一个
        assertEquals(
            BigmeEink.Direction.L2R,
            GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions, BigmeEink.Direction.R2L)
        )
        assertEquals(
            BigmeEink.Direction.R2L,
            GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions, BigmeEink.Direction.L2R)
        )
        // 最近滑动与本功能无关（或没有最近滑动）时，按 DIRECTIONS 顺序取第一个匹配（L2R），
        // 反向即 R2L —— 这条是确定性回退，不该随机
        assertEquals(
            BigmeEink.Direction.R2L,
            GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions, BigmeEink.Direction.B2T)
        )
        assertEquals(
            BigmeEink.Direction.R2L,
            GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions, null)
        )
    }

    @Test fun boundDirectionFallsBackDeterministically() {
        val actions = mapOf(
            BigmeEink.Direction.R2L to Constants.Action.OpenSettings,
            BigmeEink.Direction.B2T to Constants.Action.OpenLettersScreen
        )
        assertEquals(
            BigmeEink.Direction.R2L,
            GestureBindings.boundDirection(Constants.Action.OpenSettings, actions, null)
        )
        assertNull(GestureBindings.boundDirection(Constants.Action.OpenHub, actions, null))
    }
}
