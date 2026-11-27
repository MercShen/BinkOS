package com.github.gezimos.inkos.helper

import org.junit.Assert.*
import org.junit.Test

/**
 * 智能刷新模式的波形归属规则（纯函数）。
 *
 * 规则本身是「谁拥有 Regal、关掉还回去、读不到就不动」，全部可以在 JVM 里钉死，
 * 不需要真机；真机只负责验证反射那条读/写通道确实工作。
 */
class SmartRefreshPolicyTest {

    private val regal = BigmeEink.MODE_REGAL
    private val extreme = BigmeEink.MODE_SUPER
    private val hd256 = BigmeEink.MODE_HD256
    private val fast = 178   // 厂商默认波形（B651 实测 ro.vendor.xrz.default_refresh_mode=178）

    // ---------- 开启 ----------

    @Test fun enablingWithoutReadableModeIsRefused() {
        // 读不到原波形就没有可靠的还原，必须拒绝开启
        assertNull(SmartRefreshPolicy.on(null))
    }

    @Test fun enablingRemembersAndSwitchesToRegal() {
        val d = SmartRefreshPolicy.on(fast)!!
        assertEquals(fast, d.remember)
        assertEquals(regal, d.writeMode)
        assertEquals(regal, SmartRefreshPolicy.baselineOf(d))
    }

    @Test fun enablingWhenAlreadyRegalDoesNotRewriteThePolicy() {
        // 已经是 Regal 还写一次 = 一次无意义的策略 apply（观感上是一次全刷）
        val d = SmartRefreshPolicy.on(regal)!!
        assertEquals(regal, d.remember)
        assertNull(d.writeMode)
        assertEquals(regal, SmartRefreshPolicy.baselineOf(d))
    }

    @Test fun enablingWhenAlreadyHd256KeepsItAndDoesNotForceRegal() {
        // HD256 同样能渲染擦除，不该被强行改成 Regal
        val d = SmartRefreshPolicy.on(hd256)!!
        assertEquals(hd256, d.remember)
        assertNull(d.writeMode)
        assertEquals(hd256, SmartRefreshPolicy.baselineOf(d))
    }

    // ---------- 关闭 ----------

    @Test fun disablingRestoresTheModeFromBeforeEnabling() {
        assertEquals(fast, SmartRefreshPolicy.restore(before = fast, baseline = regal, current = regal))
    }

    @Test fun disablingWithoutRecordedModeTouchesNothing() {
        assertNull(SmartRefreshPolicy.restore(SmartRefreshPolicy.UNKNOWN, regal, regal))
    }

    @Test fun disablingCannotReadCurrentModeTouchesNothing() {
        // 此刻它可能已经被用户改过，写回会覆盖他的选择
        assertNull(SmartRefreshPolicy.restore(before = fast, baseline = regal, current = null))
    }

    @Test fun disablingDoesNotClobberAManualChange() {
        // 开启期间用户自己去厂商菜单改成极速：关闭时不覆盖
        assertNull(SmartRefreshPolicy.restore(before = fast, baseline = regal, current = extreme))
        // 改成别的任何值也一样不覆盖
        assertNull(SmartRefreshPolicy.restore(before = fast, baseline = regal, current = hd256))
    }

    @Test fun disablingWhileAnInteractionIsRunningStillRestores() {
        // 在编辑模式里直接关开关：当前那个极速是我们自己写的，不是用户改的，必须还原
        assertEquals(
            fast,
            SmartRefreshPolicy.restore(
                before = fast, baseline = regal, current = extreme, interactionMode = extreme
            )
        )
        // 但只有明确交出 interactionMode 时才放行，否则仍按「用户改过」处理
        assertNull(
            SmartRefreshPolicy.restore(before = fast, baseline = regal, current = extreme)
        )
    }

    @Test fun disablingWhenTheUserAlreadyHadRegalIsAHarmlessNoOp() {
        assertEquals(regal, SmartRefreshPolicy.restore(before = regal, baseline = regal, current = regal))
    }

    // ---------- 交互结束回到基线 ----------

    @Test fun interactionEndOnlyWritesWhileTheSwitchIsOn() {
        // 开关关了，关闭流程已经还回去了，这里再写基线等于把刚还的东西抢回来
        assertNull(SmartRefreshPolicy.interactionBaseline(smartRefreshOn = false, before = fast, baseline = regal))
    }

    @Test fun interactionEndReturnsToTheRecordedBaseline() {
        assertEquals(regal, SmartRefreshPolicy.interactionBaseline(true, before = fast, baseline = regal))
        assertEquals(hd256, SmartRefreshPolicy.interactionBaseline(true, before = hd256, baseline = hd256))
    }

    @Test fun interactionEndFallsBackToRememberedModeWhenBaselineMissing() {
        assertEquals(
            fast,
            SmartRefreshPolicy.interactionBaseline(true, before = fast, baseline = SmartRefreshPolicy.UNKNOWN)
        )
    }

    @Test fun interactionEndFallsBackToRegalForUpgradersWithoutMemory() {
        // 开关开着但没有记忆（从旧版本升级上来）：兜底回 Regal，否则会永久停在极速(179)
        assertEquals(
            regal,
            SmartRefreshPolicy.interactionBaseline(
                smartRefreshOn = true,
                before = SmartRefreshPolicy.UNKNOWN,
                baseline = SmartRefreshPolicy.UNKNOWN
            )
        )
        // 只缺 baseline、有 before 时，用 before 兜底（那是我们实际写进去过的那个值）
        assertEquals(
            fast,
            SmartRefreshPolicy.interactionBaseline(true, before = fast, baseline = SmartRefreshPolicy.UNKNOWN)
        )
    }
}
