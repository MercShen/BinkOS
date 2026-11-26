package com.github.gezimos.inkos.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * 一页列表的「焦点边界」登记表。
 *
 * 存在的理由：Compose 只在**向前**遍历（DOWN/Next）时给初始焦点，向后（UP）没有
 * 「从末尾进入」这条规则。实测刚进设置页按 UP 是 0 像素变化——键盘用户既看不到焦点，
 * 也永远够不到列表最后一条。
 *
 * 为什么不选别的做法（都试过/都查过）：
 *  - 不可见的头/尾焦点锚点：1.dp 的 Box 抢不到焦点，还会顶掉 DOWN 原有的默认初始焦点，
 *    把「进页面按 DOWN 聚焦第一条」弄坏；
 *  - 进页面主动 requestFocus：纯触摸设备会因此凭空多出焦点色块（实测触摸点击本来
 *    **不会**留下任何色块，不能破坏这一点）；
 *  - 查询「当前有没有节点被聚焦」：Compose 没有稳定的公开 API（`FocusOwner.focusedNode`
 *    /`hasFocus` 是 internal/实验），而这套行为必须依赖这个判断。
 *
 * 所以让每个行组件自己登记：注册顺序 = 组合顺序 = 视觉顺序，于是「第一条 / 最后一条」
 * 是可知的，「无人聚焦」= 登记表里没有任何条目报告聚焦，也不需要 internal API。
 */
class PageFocusEdges {

    /** 一行的登记凭据；用 index 做槽位，行增删（开关展开出额外设置）时顺序自然跟着组合走。 */
    inner class Entry internal constructor(internal val requester: FocusRequester) {
        internal var focused = false
        internal var alive = true
    }

    private val entries = mutableListOf<Entry>()

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

    /** 本页是否已经有一条被聚焦着——只有完全没有时才该接管上下键。 */
    val anyFocused: Boolean
        get() = entries.any { it.alive && it.focused }

    /** 焦点给最后一条（Compose 会把它滚进视口）。 */
    fun focusLast() {
        val last = entries.lastOrNull { it.alive } ?: return
        try { last.requester.requestFocus() } catch (_: Exception) {}
    }

    /** 焦点给第一条。原本这是 Compose 的默认行为，接管后语义不变、但变成显式的。 */
    fun focusFirst() {
        val first = entries.firstOrNull { it.alive } ?: return
        try { first.requester.requestFocus() } catch (_: Exception) {}
    }
}

/**
 * 没有提供时行组件就是普通行为（sheet、其他界面复用同一个行组件时不受影响）。
 */
val LocalPageFocusEdges = compositionLocalOf<PageFocusEdges?> { null }

/**
 * 行组件用：把「我」登记进本页的焦点边界表，并回报焦点变化。
 * 返回值要 `.then()` 进行组件的 modifier 链，`focusRequester` 必须和它的
 * `clickable`/`focusable` 落在**同一个节点**上，所以只能在行内部拿。
 */
@Composable
fun Modifier.pageEdge(isFocused: Boolean): Modifier {
    val edges = LocalPageFocusEdges.current ?: return this
    val requester = remember { FocusRequester() }
    val entry = remember(requester) { edges.register(requester) }
    DisposableEffect(entry) {
        onDispose { edges.unregister(entry) }
    }
    LaunchedEffect(isFocused, entry) {
        edges.setFocused(entry, isFocused)
    }
    return this.focusRequester(requester)
}
