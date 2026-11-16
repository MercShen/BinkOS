package com.github.gezimos.inkos.ui.compose

/**
 * 分页页码点的滑动窗口起点。
 *
 * 弹窗里弹出键盘后可视高度变小、页数会暴涨（实测从 2 页变 4 页，更多应用时会到十几页），
 * 所以页码点最多画 [maxDots] 个，当前页必须始终落在窗口里。
 *
 * 单独抽成纯函数是为了能写 JVM 单测：组合函数里那段循环没法在单测里验证。
 */
internal fun pagedDotWindowStart(currentPage: Int, pageCount: Int, maxDots: Int): Int {
    require(maxDots >= 1) { "maxDots must be >= 1" }
    if (pageCount <= maxDots) return 0
    val lastStart = pageCount - maxDots
    val centered = currentPage - maxDots / 2
    return centered.coerceIn(0, lastStart)
}
