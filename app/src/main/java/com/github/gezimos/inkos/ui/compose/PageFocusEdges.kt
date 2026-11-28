package com.github.gezimos.inkos.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent

/**
 * 一页列表的「焦点边界」登记表，并负责上下键的**循环遍历**。
 *
 * 存在的理由：Compose 只在**向前**遍历（DOWN/Next）时给初始焦点，向后（UP）没有
 * 「从末尾进入」这条规则，也不会在首/末条之间回环。键盘用户因此既看不到焦点、
 * 也永远绕不回列表另一端。
 *
 * 为什么不选别的做法（都试过/都查过）：
 *  - 不可见的头/尾焦点锚点：1.dp 的 Box 抢不到焦点，还会顶掉 DOWN 原有的默认初始焦点；
 *  - 进页面主动 requestFocus：实测触摸点击本来**不会**留下任何焦点色块，无条件抢焦点
 *    会让纯触摸设备每次进设置凭空多出一个反色块；
 *  - 查询「当前有没有节点被聚焦」：`FocusOwner.focusedNode` / `hasFocus` 是 internal，
 *    不能依赖。
 *
 * 第一版（2026-11-27）把回报做成「行组件传 `isFocused` 进来 + `LaunchedEffect` 写入」，
 * 实测不可靠：高级页里 DOWN 会在「第一条」和「无焦点」之间来回摆，UP 则一次跳到最后一条
 * ——因为回报既滞后又依赖调用方传对值。现在改成在**挂 focusRequester 的同一个节点**上用
 * [onFocusEvent] 同步回报，调用方不需要也不能再传状态进来。
 */
class PageFocusEdges {

    /** 一行的登记凭据。列表顺序 = 组合顺序 = 视觉顺序。 */
    inner class Entry internal constructor(internal val requester: FocusRequester) {
        internal var focused = false
        internal var alive = true

        /**
         * 这一行当下值不值得被聚焦。禁用行在 Compose 里**仍然是可聚焦节点**
         * （`clickable(enabled = false)` 只是不响应点击），所以 `requestFocus()` 不会抛异常、
         * 焦点真的会落上去 —— 用户看到的就是"按 DOWN 有一拍焦点消失了"（禁用行是灰的，
         * 高亮不明显）。所以必须由行自己把这个开关告诉登记表，遍历时直接跳过。
         */
        internal var canFocus = true
    }

    private val entries = mutableListOf<Entry>()

    private val aliveEntries: List<Entry> get() = entries.filter { it.alive }

    private val steppable: List<Entry> get() = aliveEntries.filter { it.canFocus }

    fun register(requester: FocusRequester): Entry {
        val e = Entry(requester)
        entries += e
        return e
    }

    fun unregister(entry: Entry) {
        entry.alive = false
        entry.focused = false
        entries.remove(entry)
    }

    fun setFocused(entry: Entry, value: Boolean) {
        if (!entry.alive) return
        entry.focused = value
    }

    /** 行组件同步自己是否可聚焦（禁用行传 false，遍历会跳过它）。 */
    fun setCanFocus(entry: Entry, value: Boolean) {
        if (!entry.alive) return
        entry.canFocus = value
        if (!value) entry.focused = false
    }

    /** 当前被聚焦的是第几条（-1 = 本页没有任何一条持有焦点）。 */
    val focusedIndex: Int
        get() = steppable.indexOfFirst { it.focused }

    /**
     * 从 [from] 开始沿 [step] 方向找下一个可聚焦条目，找不到就绕到另一端再找一遍。
     *
     * 为什么不交回 Compose 遍历：交回去会落在禁用行上（见 [Entry.canFocus]），
     * 实测高级页第二条是禁用开关时 DOWN 会有一拍"焦点消失"。登记表顺序就是视觉顺序，
     * 自己走这一圈比几何遍历更可预测。
     */
    private fun focusStepping(from: Int, step: Int): Boolean {
        val list = steppable
        if (list.isEmpty()) return false
        val size = list.size
        val start = if (from >= 0) (((from + step) % size) + size) % size else if (step > 0) 0 else size - 1
        for (i in 0 until size) {
            val e = list[(((start + i * step) % size) + size) % size]
            try {
                e.requester.requestFocus()
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun focusEdge(fromEnd: Boolean): Boolean = focusStepping(if (fromEnd) 0 else -1, if (fromEnd) -1 else 1)

    fun focusFirst(): Boolean = focusStepping(-1, 1)

    fun focusLast(): Boolean = focusStepping(-1, -1)

    /**
     * DOWN：无焦点 → 第一条；否则下一条，**到底自动回环到第一条**；禁用行自动跳过。
     *
     * 上下键完全由这里解释，不交回 Compose 遍历 —— 交回去会把焦点丢在禁用行上
     * （见 [focusStepping]）。登记表为空时返回 false，不吞键。
     */
    fun onDown(): Boolean {
        if (steppable.isEmpty()) return false
        focusStepping(focusedIndex, 1)
        return true
    }

    /** UP：无焦点 → 最后一条；否则上一条，**到顶自动回环到最后一条**；禁用行自动跳过。 */
    fun onUp(): Boolean {
        if (steppable.isEmpty()) return false
        focusStepping(focusedIndex, -1)
        return true
    }
}

/**
 * 没有提供时行组件保持普通行为（sheet、其他界面复用同一批行组件时不受影响）。
 */
val LocalPageFocusEdges = compositionLocalOf<PageFocusEdges?> { null }

/**
 * 行组件用：把「我」登记进本页的焦点边界表。
 *
 * `focusRequester` 必须和该行的 `clickable`/`focusable` 落在**同一个节点**上，
 * 所以只能在行内部挂；回报也挂在同一节点上，这样「谁被聚焦」由 Compose 直接告诉我们，
 * 不再靠调用方传状态（第一版那么做，回报滞后且容易传错，见类注释）。
 *
 * @param canFocus 这一行当下是否应该被聚焦（禁用行传 false，遍历会跳过它）
 */
@Composable
fun Modifier.pageEdge(canFocus: Boolean = true): Modifier {
    val edges = LocalPageFocusEdges.current ?: return this
    val requester = remember { FocusRequester() }
    val entry = remember(requester) { edges.register(requester) }
    DisposableEffect(entry) {
        onDispose { edges.unregister(entry) }
    }
    LaunchedEffect(entry, canFocus) {
        edges.setCanFocus(entry, canFocus)
    }
    return this
        .focusRequester(requester)
        .onFocusEvent { state -> edges.setFocused(entry, state.isFocused) }
}
