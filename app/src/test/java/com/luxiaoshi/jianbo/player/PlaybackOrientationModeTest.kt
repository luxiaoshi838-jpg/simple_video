package com.luxiaoshi.jianbo.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackOrientationModeTest {
    @Test
    fun toolbarModeCyclesAdaptivePortraitLandscape() {
        assertEquals(
            PlaybackOrientationMode.PORTRAIT,
            nextPlaybackOrientationMode(PlaybackOrientationMode.ADAPTIVE),
        )
        assertEquals(
            PlaybackOrientationMode.LANDSCAPE,
            nextPlaybackOrientationMode(PlaybackOrientationMode.PORTRAIT),
        )
        assertEquals(
            PlaybackOrientationMode.ADAPTIVE,
            nextPlaybackOrientationMode(PlaybackOrientationMode.LANDSCAPE),
        )
    }

    @Test
    fun orientationTargetKeepsAdaptiveAndForcesFixedModes() {
        assertEquals(true, orientationTarget(PlaybackOrientationMode.ADAPTIVE, true))
        assertEquals(false, orientationTarget(PlaybackOrientationMode.ADAPTIVE, false))
        assertNull(orientationTarget(PlaybackOrientationMode.ADAPTIVE, null))
        assertEquals(false, orientationTarget(PlaybackOrientationMode.PORTRAIT, true))
        assertEquals(true, orientationTarget(PlaybackOrientationMode.LANDSCAPE, false))
    }
}
