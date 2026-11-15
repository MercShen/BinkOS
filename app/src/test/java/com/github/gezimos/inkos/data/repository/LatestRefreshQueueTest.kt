package com.github.gezimos.inkos.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestRefreshQueueTest {

    @Test
    fun laterRequestWinsWithinSameKind() {
        val queue = LatestRefreshQueue()
        queue.request(includeHiddenApps = false, flag = "Drawer")
        val last = queue.request(includeHiddenApps = false, flag = null)
        val job = queue.take()!!
        assertEquals(last, job.generation)
        assertNull(job.flag)
        assertNull(queue.take())
        assertFalse(queue.hasPending())
    }

    @Test
    fun hiddenRequestDoesNotDropPendingVisibleRequest() {
        val queue = LatestRefreshQueue()
        queue.request(includeHiddenApps = false, flag = "Visible")
        queue.request(includeHiddenApps = true, flag = null)
        val first = queue.take()!!
        assertEquals("Visible", first.flag)
        assertFalse(first.includeHiddenApps)
        val second = queue.take()!!
        assertTrue(second.includeHiddenApps)
        assertNull(queue.take())
    }

    @Test
    fun conflationKeepsNewestPendingPerKind() {
        val queue = LatestRefreshQueue()
        queue.request(false, "A")
        queue.request(false, "B")
        val job = queue.take()!!
        assertEquals("B", job.flag)
        assertNull(queue.take())
    }

    @Test
    fun generationIsMonotonic() {
        val queue = LatestRefreshQueue()
        val first = queue.request(false, null)
        val second = queue.request(true, null)
        assertTrue(second > first)
        assertEquals(second, queue.lastRequestedGeneration())
    }

    @Test
    fun staleGenerationMustNotPublishOverNewer() {
        assertFalse(LatestRefreshQueue.shouldPublish(3L, 5L))
        assertFalse(LatestRefreshQueue.shouldPublish(5L, 5L))
        assertTrue(LatestRefreshQueue.shouldPublish(6L, 5L))
    }
}
