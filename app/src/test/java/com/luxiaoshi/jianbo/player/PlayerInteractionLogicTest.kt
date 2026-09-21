package com.luxiaoshi.jianbo.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerInteractionLogicTest {
    @Test
    fun proportionalHorizontalSeekAdaptsToCurrentScreenWidth() {
        val duration = 120_000L
        val start = 30_000L
        assertEquals(60_000L, seekPositionForHorizontalDrag(start, duration, 270f, 1080f))
        assertEquals(60_000L, seekPositionForHorizontalDrag(start, duration, 600f, 2400f))
        assertEquals(43_500L, seekPositionForHorizontalDrag(start, duration, 270f, 2400f))
    }

    @Test
    fun horizontalSeekClampsToVideoBounds() {
        val duration = 120_000L
        assertEquals(0L, seekPositionForHorizontalDrag(30_000L, duration, -10_000f, 1080f))
        assertEquals(duration, seekPositionForHorizontalDrag(30_000L, duration, 10_000f, 1080f))
    }

    @Test
    fun completedVideoDoesNotResumeFromTheEnd() {
        assertEquals(42_000L, normalizeResumePosition(42_000L, 120_000L))
        assertEquals(0L, normalizeResumePosition(120_000L, 120_000L))
        assertEquals(0L, normalizeResumePosition(130_000L, 120_000L))
        assertEquals(42_000L, normalizeResumePosition(42_000L, 0L))
    }
}
