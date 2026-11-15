package com.github.gezimos.inkos.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationHistoryLimitsTest {

    private fun rows(vararg pairs: Pair<String, Long>) =
        pairs.map { NotificationHistoryLimits.Row(it.first, it.second) }

    @Test
    fun boundedKeepsNewestAndCapsSize() {
        val input = rows("a" to 1L, "b" to 5L, "c" to 3L, "d" to 9L)
        val out = NotificationHistoryLimits.bounded(input, max = 2)
        assertEquals(listOf("d", "b"), out.map { it.id })
        assertEquals(2, out.size)
    }

    @Test
    fun boundedDoesNotDropAnythingBelowCap() {
        val input = rows("a" to 1L, "b" to 2L)
        val out = NotificationHistoryLimits.bounded(input, max = 40)
        assertEquals(2, out.size)
        assertEquals(setOf("a", "b"), out.map { it.id }.toSet())
    }

    @Test
    fun mergePreferCurrentKeepsRuntimeAndRestoresMissing() {
        val current = mapOf("pkg" to rows("new" to 100L))
        val restored = mapOf("pkg" to rows("old" to 50L, "new" to 10L))
        val merged = NotificationHistoryLimits.mergePreferCurrent(current, restored, max = 40)
        val byId = merged.getValue("pkg").associateBy { it.id }
        assertEquals(100L, byId.getValue("new").timestamp) // runtime wins
        assertEquals(50L, byId.getValue("old").timestamp) // restored only fills missing
        assertEquals(2, merged.getValue("pkg").size)
    }

    @Test
    fun mergeDropsEmptyPackages() {
        val merged = NotificationHistoryLimits.mergePreferCurrent(emptyMap(), mapOf("pkg" to emptyList()))
        assertTrue(merged.isEmpty())
    }

    @Test
    fun effectiveCapNeverTrimsAlreadyLoadedHistory() {
        assertEquals(500, NotificationHistoryLimits.effectiveCap(10))
        assertEquals(1000, NotificationHistoryLimits.effectiveCap(1000))
    }

    @Test
    fun tombstonedIdsAreNotResurrectedByRestore() {
        val current = emptyMap<String, List<NotificationHistoryLimits.Row>>()
        val restored = mapOf("pkg" to rows("removed" to 100L, "kept" to 50L))
        val merged = NotificationHistoryLimits.mergePreferCurrent(
            current, restored, Int.MAX_VALUE, tombstones = setOf("pkg|removed")
        )
        val ids = merged.getValue("pkg").map { it.id }.toSet()
        assertEquals(setOf("kept"), ids)
    }
}
