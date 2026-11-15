package com.github.gezimos.inkos.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTickDelayTest {
    private val utc = java.util.TimeZone.getTimeZone("UTC")

    @Test
    fun alignsToNextMinute() {
        val base = 1_700_000_000_000L
        val now = base - base % 60_000L + 59_000L // exactly :59
        assertEquals(1000L, ClockTickDelay.untilNextMinute(now, utc))
    }

    @Test
    fun exactlyOnMinuteWaitsFullMinute() {
        val now = 1_700_000_040_000L // :40? ensure seconds 0
        val onMinute = now - (now % 60_000L)
        assertEquals(60_000L, ClockTickDelay.untilNextMinute(onMinute, utc))
    }

    @Test
    fun delayIsAlwaysPositiveBounded() {
        val now = 1_700_000_000_000L
        val delay = ClockTickDelay.untilNextMinute(now, utc)
        assertTrue(delay in 1L..60_000L)
    }
}
