package com.github.gezimos.inkos.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedDotWindowTest {

    @Test
    fun fewerPagesThanCapStartsAtZero() {
        assertEquals(0, pagedDotWindowStart(currentPage = 0, pageCount = 4, maxDots = 8))
        assertEquals(0, pagedDotWindowStart(currentPage = 3, pageCount = 4, maxDots = 8))
        assertEquals(0, pagedDotWindowStart(currentPage = 7, pageCount = 8, maxDots = 8))
    }

    @Test
    fun capsVisibleDotsAtMaxDots() {
        // 键盘弹出后页数暴涨的极端值：20 页也只画 8 个点。
        for (page in 0 until 20) {
            val start = pagedDotWindowStart(page, pageCount = 20, maxDots = 8)
            val end = start + 8
            assertTrue("page $page must be inside the window", page in start until end)
            assertEquals("window stays at 8 dots", 8, end - start)
            assertTrue("window never goes negative", start >= 0)
            assertTrue("window never past the last page", end <= 20)
        }
    }

    @Test
    fun keepsCurrentPageNearTheMiddle() {
        assertEquals(0, pagedDotWindowStart(currentPage = 0, pageCount = 20, maxDots = 8))
        assertEquals(1, pagedDotWindowStart(currentPage = 5, pageCount = 20, maxDots = 8))
        assertEquals(2, pagedDotWindowStart(currentPage = 6, pageCount = 20, maxDots = 8))
        assertEquals(12, pagedDotWindowStart(currentPage = 16, pageCount = 20, maxDots = 8))
        assertEquals(12, pagedDotWindowStart(currentPage = 19, pageCount = 20, maxDots = 8))
    }

    @Test
    fun singlePageNeverNeedsAWindow() {
        assertEquals(0, pagedDotWindowStart(currentPage = 0, pageCount = 1, maxDots = 8))
    }
}
