package com.github.gezimos.inkos.helper

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SnapshotAndSlotTest {
    @Test fun cacheRunsOnlyOneLoaderForConcurrentRequests() = runBlocking {
        var calls = 0
        val cache = SingleFlightSnapshot<String>(1000L) { 100L }
        val jobs = (1..20).map { async { cache.get { calls++; delay(10); "loaded" } } }
        assertTrue(jobs.all { it.await() == "loaded" })
        assertEquals(1, calls)
    }
    @Test fun invalidatedInflightLoadIsNeverPublished() = runBlocking {
        val cache = SingleFlightSnapshot<String>(1000L) { 100L }
        var calls = 0
        val result = cache.get { calls++; if (calls == 1) cache.invalidate(); "v$calls" }
        assertEquals("v2", result)
        assertEquals("v2", cache.peek())
    }
    @Test fun ttlAndClockRegressionRefreshSnapshot() = runBlocking {
        var clock = 100L
        var count = 0
        val cache = SingleFlightSnapshot<Int>(100L) { clock }
        assertEquals(1, cache.get { ++count })
        clock = 150L
        assertEquals(1, cache.get { ++count })
        clock = 200L
        assertEquals(2, cache.get { ++count })
        clock = 0L
        assertEquals(3, cache.get { ++count })
    }
    @Test fun latestSheetWinsAndHomeInvalidatesPendingWork() {
        val slot = LatestTaskSlot<Any>()
        val a = Any(); val b = Any()
        val old = slot.replace(a)
        val next = slot.replace(b)
        assertFalse(slot.owns(a, old))
        slot.cancel(a)
        assertTrue(slot.owns(b, next))
        slot.cancelAll()
        assertFalse(slot.owns(b, next))
    }
}
