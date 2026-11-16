package com.github.gezimos.inkos.helper

import com.github.gezimos.inkos.data.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureBindingsTest {

    private fun bindings(vararg pairs: Pair<BigmeEink.Direction, Constants.Action>) = pairs.toMap()

    @Test
    fun unboundActionHasNoReverseCloseDirection() {
        val actions = bindings(
            BigmeEink.Direction.L2R to Constants.Action.Disabled,
            BigmeEink.Direction.R2L to Constants.Action.Disabled,
            BigmeEink.Direction.T2B to Constants.Action.Disabled,
            BigmeEink.Direction.B2T to Constants.Action.Disabled,
        )

        assertNull(GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions, recent = null))
        // 最近一次下滑绑定的是别的功能，也不能替 OpenSettings 打开反向关闭。
        assertNull(GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions, recent = BigmeEink.Direction.T2B))
    }

    @Test
    fun boundActionClosesWithOppositeDirection() {
        val actions = bindings(
            BigmeEink.Direction.L2R to Constants.Action.Disabled,
            BigmeEink.Direction.R2L to Constants.Action.Disabled,
            BigmeEink.Direction.T2B to Constants.Action.OpenSettings, // 手指向下打开设置
            BigmeEink.Direction.B2T to Constants.Action.Disabled,
        )

        assertEquals(
            BigmeEink.Direction.B2T, // 反向：手指向上关闭
            GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions, recent = null)
        )
    }

    @Test
    fun recentBindingWinsWhenActionHasMultipleDirections() {
        val actions = bindings(
            BigmeEink.Direction.L2R to Constants.Action.OpenSettings,
            BigmeEink.Direction.R2L to Constants.Action.Disabled,
            BigmeEink.Direction.T2B to Constants.Action.OpenSettings,
            BigmeEink.Direction.B2T to Constants.Action.Disabled,
        )

        // 没有最近手势时取第一个绑定方向（L2R → 关闭 R2L）。
        assertEquals(
            BigmeEink.Direction.R2L,
            GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions, recent = null)
        )
        // 最近确实是下滑打开，则关应用上滑。
        assertEquals(
            BigmeEink.Direction.B2T,
            GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions, recent = BigmeEink.Direction.T2B)
        )
    }

    @Test
    fun otherActionBindingDoesNotEnableClose() {
        val actions = bindings(
            BigmeEink.Direction.T2B to Constants.Action.OpenHub,
            BigmeEink.Direction.B2T to Constants.Action.OpenLettersScreen,
        )

        assertNull(GestureBindings.closeDirectionFor(Constants.Action.OpenSettings, actions, recent = BigmeEink.Direction.T2B))
    }
}
