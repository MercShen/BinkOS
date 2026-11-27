package com.github.gezimos.inkos.helper

import org.junit.Assert.*
import org.junit.Test

/**
 * 厂商临时动画窗口的下发节奏规则。
 *
 * 这条规则之所以存在，是因为线上事故：`THROTTLE_MS=300 < DURATION_MS=350`，
 * 第二次下发落在第一个窗口到期之前（B651 抓到过 340ms 的间隔），厂商单槽被覆盖，
 * 整屏提交带着上一次手势的方向播出 —— 画面与手指错相位。
 */
class AnimationWindowPolicyTest {

    private val duration = 350L
    private val gap = duration + AnimationWindowPolicy.MARGIN_MS
    private val cooldown = AnimationWindowPolicy.BURST_COOLDOWN_MS

    private fun st(lastArmAt: Long = 0L, lastType: Int = 0, burstUntil: Long = 0L) =
        AnimationWindowPolicy.State(lastArmAt, lastType, burstUntil)

    private fun decide(state: AnimationWindowPolicy.State, now: Long, type: Int, d: Long = duration) =
        AnimationWindowPolicy.decide(state, now, type, d)

    private fun run(events: List<Pair<Long, Int>>, d: Long = duration): Pair<Int, AnimationWindowPolicy.State> {
        var state = st()
        var armed = 0
        for ((t, type) in events) {
            when (val r = decide(state, t, type, d)) {
                is AnimationWindowPolicy.Decision.Arm -> { armed++; state = r.state }
                is AnimationWindowPolicy.Decision.EnterBurst -> state = r.state
                is AnimationWindowPolicy.Decision.Drop -> {}
            }
        }
        return armed to state
    }

    // ---------- 正常下发 ----------

    @Test fun firstTriggerArms() {
        val r = decide(st(), 1000L, 3)
        assertTrue(r is AnimationWindowPolicy.Decision.Arm)
        val s = (r as AnimationWindowPolicy.Decision.Arm).state
        assertEquals(1000L, s.lastArmAt)
        assertEquals(0L, s.burstUntil)
    }

    @Test fun triggerAfterTheWindowLapsedArmsAgain() {
        val a = (decide(st(), 1000L, 3) as AnimationWindowPolicy.Decision.Arm).state
        assertTrue(decide(a, 1000L + gap + 10, 4) is AnimationWindowPolicy.Decision.Arm)
    }

    // ---------- 重复下发 vs 撞窗口 ----------

    @Test fun trueDuplicateIsOnlyDeduplicated() {
        // 同一次回调被送两遍（间隔 <= DUPLICATE_MS）：去重，不进冷却
        val a = (decide(st(), 1000L, 3) as AnimationWindowPolicy.Decision.Arm).state
        val r = decide(a, 1000L + AnimationWindowPolicy.DUPLICATE_MS, 3)
        assertTrue("极短间隔的同样式重复只该去重", r is AnimationWindowPolicy.Decision.Drop)
    }

    @Test fun sameDirectionButNotAnInstantDuplicateStartsCooldown() {
        // 一路同方向连续翻页（每 300ms 一次 type=3）：那是连续操作，不是重复下发，
        // 必须进冷却 —— 否则整个拖动过程每 410ms 就擦一次
        val a = (decide(st(), 1000L, 3) as AnimationWindowPolicy.Decision.Arm).state
        val r = decide(a, 1300L, 3)
        assertTrue(r is AnimationWindowPolicy.Decision.EnterBurst)
    }

    @Test fun differentOperationInsideWindowEntersBurstCooldown() {
        val a = (decide(st(), 1000L, 3) as AnimationWindowPolicy.Decision.Arm).state
        val r = decide(a, 1340L, 4)
        assertTrue(r is AnimationWindowPolicy.Decision.EnterBurst)
        assertEquals(1340L + cooldown, (r as AnimationWindowPolicy.Decision.EnterBurst).state.burstUntil)
    }

    @Test fun continuousActivityKeepsExtendingTheQuietPeriod() {
        // 冷却中又来操作：静默期往后推，而不是等它过期后再漏一次擦除
        val cooling = st(lastArmAt = 1000L, lastType = 3, burstUntil = 3000L)
        val r = decide(cooling, 2500L, 4)
        assertTrue(r is AnimationWindowPolicy.Decision.EnterBurst)
        assertEquals(2500L + cooldown, (r as AnimationWindowPolicy.Decision.EnterBurst).state.burstUntil)
    }

    @Test fun normalArmingResumesAfterTheDragEnds() {
        val cooling = st(lastArmAt = 1000L, lastType = 3, burstUntil = 3000L)
        val r = decide(cooling, 3100L, 4)
        assertTrue("拖动停了之后必须恢复正常下发，否则动画永久消失", r is AnimationWindowPolicy.Decision.Arm)
        assertEquals(0L, (r as AnimationWindowPolicy.Decision.Arm).state.burstUntil)
    }

    // ---------- 窗口长度参与判定 ----------

    @Test fun longerWindowNeedsLongerGap() {
        val a = (decide(st(), 1000L, 10, 800L) as AnimationWindowPolicy.Decision.Arm).state
        assertTrue(decide(a, 1500L, 9, 800L) !is AnimationWindowPolicy.Decision.Arm)
        assertTrue(
            decide(a, 1000L + 800L + AnimationWindowPolicy.MARGIN_MS + 5, 9, 800L)
                is AnimationWindowPolicy.Decision.Arm
        )
    }

    // ---------- 现场场景 ----------

    @Test fun sustainedDragArmsExactlyOnce() {
        // 手指按住拖动：每 300ms 一次、方向来回交替，共 20 次 —— 只该擦第一下
        val events = (0 until 20).map { (1000L + it * 300L) to (if (it % 2 == 0) 3 else 4) }
        val (armed, _) = run(events)
        assertEquals("连续拖动期间只该出现一次擦除", 1, armed)
    }

    @Test fun sustainedSameDirectionDragAlsoArmsOnce() {
        val events = (0 until 20).map { (1000L + it * 300L) to 3 }
        val (armed, _) = run(events)
        assertEquals("同方向连续翻页同样只该擦一次", 1, armed)
    }

    @Test fun slowDeliberatePagingStillAnimatesEveryTime() {
        // 反面教材不能伤到正常用法：一次一次慢慢翻（500ms > 窗口+余量）必须每次都擦
        val events = (0 until 6).map { (1000L + it * 500L) to (if (it % 2 == 0) 3 else 4) }
        val (armed, _) = run(events)
        assertEquals(6, armed)
    }

    @Test fun discreteNavigationsAfterIdleAlwaysArm() {
        // 每次间隔 1200ms（远大于窗口+冷却）：完全不该被误判成突发
        val events = (0 until 8).map { (1000L + it * 1200L) to (if (it % 2 == 0) 3 else 4) }
        val (armed, _) = run(events)
        assertEquals(8, armed)
    }
}
