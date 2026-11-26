package com.github.gezimos.inkos.helper

import android.view.KeyEvent

/**
 * 翻页快捷键的唯一判定入口（上游 issue #125：Bigme B7 之类可以把侧键映射成
 * PAGE_UP/PAGE_DOWN 以外的 keycode，不同厂商差异很大）。
 *
 * 设计要点：**不做逐屏适配，而是在 Activity 入口把按键改写成 PAGE_UP/PAGE_DOWN**。
 * 主页 zone 模型、抽屉、最近任务、Hub、Tray、Letters 以及 `Modifier.onPageKeys`
 * 本来就认这两颗键，改写之后自定义键自动在全 App 生效，不需要每加一个分页界面
 * 就再记一遍「这里也要支持自定义键」。
 *
 * 纯函数放在 [resolve]，输入只有 keycode 和三个开关，所以能在 JVM 里直接测。
 */
enum class PageDirection { Up, Down }

object PageKeyRouter {

    /** 默认绑定：Page 键永远翻页，不受开关影响（它没有别的消费者）。 */
    val defaultUpKeys = setOf(KeyEvent.KEYCODE_PAGE_UP)
    val defaultDownKeys = setOf(KeyEvent.KEYCODE_PAGE_DOWN)

    /**
     * 硬黑名单：这些键一旦绑成翻页，界面自己就废了——CENTER/ENTER 是激活键，
     * BACK/HOME/ESCAPE 是返回，MENU 开抽屉，DEL 是搜索退格，POWER 系统也收不到。
     * 绑定时直接拒绝，不弹冲突框（因为这不是「两个功能抢一颗键」，是「把钥匙扔了」）。
     */
    val blockedKeys: Set<Int> = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_DEL,
        KeyEvent.KEYCODE_FORWARD_DEL
    )

    /**
     * 软警告名单：允许绑定，但这颗键在本 App 里已经有明确职责，用户要知道自己换走了什么。
     * 方向键承担选中/换区，音量键在「音量键翻页」开着时本来就走翻页。
     */
    val sharedKeys: Set<Int> = setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN
    )

    /**
     * 这颗键此刻该不该翻页、往哪页翻。
     *
     * @param volumeKeysForPages 「音量键翻页」开关
     * @param audioPlaying       正在放音频时音量键让位给系统音量（沿用原有例外规则）
     *
     * Page 键无条件生效；自定义键其次；音量键最后且要看开关。
     */
    fun resolve(
        keyCode: Int,
        pageUpKeys: Set<Int> = emptySet(),
        pageDownKeys: Set<Int> = emptySet(),
        volumeKeysForPages: Boolean = false,
        audioPlaying: Boolean = false
    ): PageDirection? {
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP) return PageDirection.Up
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) return PageDirection.Down
        if (pageUpKeys.contains(keyCode)) return PageDirection.Up
        if (pageDownKeys.contains(keyCode)) return PageDirection.Down
        if (volumeKeysForPages && !audioPlaying) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return PageDirection.Up
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return PageDirection.Down
        }
        return null
    }

    /** 这颗键是不是已经被 Page 键本身或另一方向占用（用来给冲突提示）。 */
    fun alreadyBound(keyCode: Int, otherDirectionKeys: Set<Int>): Boolean =
        keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
            otherDirectionKeys.contains(keyCode)

    /**
     * 纯函数：这颗键在这个 action 上，应该被改写成哪颗 Page 键；不需要改写就返回 null。
     *
     * 单独拆出来是因为 JVM 单元测试里构造 `android.view.KeyEvent` 会炸（项目没有
     * Robolectric，`android.jar` 里的方法是桩），而这条判断才是真正需要被钉住的行为。
     */
    fun rewriteTarget(
        eventKeyCode: Int,
        eventAction: Int,
        pageUpKeys: Set<Int>,
        pageDownKeys: Set<Int>,
        volumeKeysForPages: Boolean,
        audioPlaying: Boolean
    ): Int? {
        if (eventAction != KeyEvent.ACTION_DOWN && eventAction != KeyEvent.ACTION_UP) return null
        val direction = resolve(
            keyCode = eventKeyCode,
            pageUpKeys = pageUpKeys,
            pageDownKeys = pageDownKeys,
            volumeKeysForPages = volumeKeysForPages,
            audioPlaying = audioPlaying
        ) ?: return null
        val target = when (direction) {
            PageDirection.Up -> KeyEvent.KEYCODE_PAGE_UP
            PageDirection.Down -> KeyEvent.KEYCODE_PAGE_DOWN
        }
        // 本来就是 Page 键就不用再造一个事件
        if (eventKeyCode == target) return null
        return target
    }

    /**
     * 需要改写成 PAGE_UP/PAGE_DOWN 时返回新事件，否则返回 null（原样分发）。
     * DOWN 和 UP 两个 action 都要改写，否则 Compose 只看到一半。
     */
    fun rewrittenEvent(
        event: KeyEvent,
        pageUpKeys: Set<Int>,
        pageDownKeys: Set<Int>,
        volumeKeysForPages: Boolean,
        audioPlaying: Boolean
    ): KeyEvent? {
        val target = rewriteTarget(
            eventKeyCode = event.keyCode,
            eventAction = event.action,
            pageUpKeys = pageUpKeys,
            pageDownKeys = pageDownKeys,
            volumeKeysForPages = volumeKeysForPages,
            audioPlaying = audioPlaying
        ) ?: return null
        // 保留 downTime / eventTime / repeatCount / metaState / device，
        // 否则长按检测（repeatCount）和来源判断都会失真。
        return KeyEvent(
            event.downTime,
            event.eventTime,
            event.action,
            target,
            event.repeatCount,
            event.metaState,
            event.deviceId,
            event.flags,
            event.source
        )
    }
}
