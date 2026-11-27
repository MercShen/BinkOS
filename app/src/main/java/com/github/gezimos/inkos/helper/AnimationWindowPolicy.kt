package com.github.gezimos.inkos.helper

/**
 * 厂商「临时动画窗口」的下发节奏判定——纯函数，JVM 可测。
 *
 * 为什么需要它（2026-11-27 B651 实测）：窗口长度 `DURATION_MS=350`，而节流只有
 * `THROTTLE_MS=300`，**节流比窗口短**，于是第二次下发落在第一个窗口到期之前
 * （抓到过 340ms 间隔的两次 home page prev）。厂商那个槽是单槽语义，后一次直接覆盖
 * 前一次，面板就会带着「上一次手势的方向」提交整屏——画面与手指错相位。
 * 改窗口长度之前是 280ms（300 > 280，每轮之间有 20ms 空档，窗口能自然落地）。
 *
 * 现在把两件事分开，不再用同一个常数硬扛：
 *  - **同一次操作被重复下发**（方向相同、间隔小于窗口）：只去重，不升级成突发。
 *  - **不同操作撞进还没落地的窗口**：说明用户在连续操作（拖动翻页就是这种），
 *    进入突发冷却，冷却期内一概不下发。拖动时「每一页都擦一次」本来就是错的观感，
 *    一次擦除都不如乱擦好几次。
 *
 * 时钟必须用 [SystemClock.elapsedRealtime] 那一类**计入休眠**的时间：
 * `uptimeMillis` 不计深度睡眠，锁屏一段时间回来，上一个窗口的时间戳会显得「刚刚发生」，
 * 于是解锁后的第一次下发被误判成突发而被吞掉，或者反过来把锁屏前的手势当成最近手势。
 */
object AnimationWindowPolicy {

    /** 窗口到期后的余量，避免边界抖动。 */
    const val MARGIN_MS = 60L

    /**
     * 「同一次操作被重复下发」的判定窗口，必须**远小于**一个动画窗口。
     *
     * 不能拿「方向相同」当重复的证据：一路往同一个方向连续翻页时方向当然不变，
     * 那是连续操作而不是重复下发。真重复是同一次回调被送了两遍，间隔极短。
     */
    const val DUPLICATE_MS = 150L

    /** 撞窗口之后静默多久不再下发。 */
    const val BURST_COOLDOWN_MS = 600L

    /**
     * @param lastArmAt 上次真正下发的时刻（0 = 从未）
     * @param lastType  上次下发的厂商动画样式（1–4 方向擦除，9/10 波纹，0 清除）
     * @param burstUntil 突发冷却截止时间（0 = 不在冷却中）
     */
    data class State(
        val lastArmAt: Long = 0L,
        val lastType: Int = 0,
        val burstUntil: Long = 0L
    )

    /**
     * 三种结果必须都能表达：撞窗口时既要**本次不下发**、又要**把状态推进成冷却中**，
     * 用「null = 不下发」会把这两件事混成一件事，所以显式三态。
     */
    sealed class Decision {
        /** 同一次操作被重复下发：吞掉，状态不变。 */
        object Drop : Decision()

        /** 撞窗口，或冷却中又来了新操作：吞掉，并把静默期推进（状态要更新）。 */
        data class EnterBurst(val state: State) : Decision()

        /** 正常下发（状态推进）。 */
        data class Arm(val state: State) : Decision()
    }

    fun decide(state: State, now: Long, type: Int, durationMs: Long): Decision {
        if (state.burstUntil > 0L && now < state.burstUntil) {
            // 冷却还没过又有操作进来 = 连续动作还在继续：把静默期往后推。
            // 不等它自然过期就重新下发，一次长拖动里就会每 (窗口+余量+冷却) 漏出一次擦除，
            // 观感仍然是「画面自己来回抽」。
            return Decision.EnterBurst(state.copy(burstUntil = now + BURST_COOLDOWN_MS))
        }
        val gap = durationMs + MARGIN_MS
        if (state.lastArmAt > 0L && now - state.lastArmAt < gap) {
            // 真·重复下发：极短时间内同一样式再来一次，只去重，不升级成突发
            if (type == state.lastType && now - state.lastArmAt <= DUPLICATE_MS) return Decision.Drop
            // 不同操作撞窗口：进突发冷却，冷却期内全部不下发
            return Decision.EnterBurst(state.copy(burstUntil = now + BURST_COOLDOWN_MS))
        }
        return Decision.Arm(State(lastArmAt = now, lastType = type, burstUntil = 0L))
    }
}
