package com.github.gezimos.inkos.ui.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.view.KeyEvent

/**
 * 把 PAGE_UP / PAGE_DOWN 变成「上一页 / 下一页」。
 *
 * 为什么这两颗键要无条件响应：墨水屏键盘机上它们是侧键映射的目标（上游 issue #125：
 * Bigme B7 可以把侧键设成发送 Page Up/Down 而不是音量，好跟浏览器之类的第三方 App
 * 自动兼容），而且它们没有别的消费者。音量键不一样——它同时是系统音量，所以才需要
 * `useVolumeKeysForPages` 这种开关和「播放中让位」的例外。
 *
 * 用 `onPreviewKeyEvent`：这些界面的根节点本来就在 preview 层拦截方向键做 zone 导航，
 * Page 键不在 zone 模型的语义里，挂在同一层最省事。KeyUp 也一并消费，避免长按重复
 * 触发时被算成两次。
 *
 * 注意：返回 true 表示吃掉这两颗键。若某个页面根本没有分页（pageCount == 1），
 * 调用方在回调里自己不动作即可，不要为了「让键传下去」而返回 false——那样按键会
 * 掉进 MainActivity 的兜底分支，产生看不见的副作用。
 */
fun Modifier.onPageKeys(
    onPrevious: () -> Unit,
    onNext: () -> Unit
): Modifier = this.onPreviewKeyEvent { event ->
    val code = event.nativeKeyEvent.keyCode
    if (code != KeyEvent.KEYCODE_PAGE_UP && code != KeyEvent.KEYCODE_PAGE_DOWN) return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyDown) {
        if (code == KeyEvent.KEYCODE_PAGE_UP) onPrevious() else onNext()
    }
    true
}
