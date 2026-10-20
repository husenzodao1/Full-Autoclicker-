package com.fullautoclicker.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyStatsTest {

    @Test
    fun emptyStatsReportNoValue() {
        val s = LatencyStats()
        assertEquals(-1, s.min())
        assertEquals(-1, s.max())
        assertEquals(-1, s.percentile(0.95))
        assertEquals(-1.0, s.average(), 0.0)
    }

    @Test
    fun summarizesSamples() {
        val s = LatencyStats()
        listOf(5L, 1L, 9L, 3L).forEach(s::record)
        assertEquals(1, s.min())
        assertEquals(9, s.max())
        assertEquals(4.5, s.average(), 1e-9)
        assertEquals(3, s.last)
        assertEquals(9, s.percentile(0.95))
        assertEquals(3, s.percentile(0.5))
    }

    @Test
    fun keepsOnlyTheMostRecentWindow() {
        val s = LatencyStats(capacity = 3)
        listOf(100L, 1L, 2L, 3L).forEach(s::record)
        assertEquals(3, s.size)
        assertEquals(4, s.total)
        assertEquals(3, s.max())
    }

    @Test
    fun negativeSamplesClampToZero() {
        val s = LatencyStats()
        s.record(-5)
        assertEquals(0, s.last)
    }
}
