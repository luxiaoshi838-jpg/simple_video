package com.luxiaoshi.jianbo.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOrientationCrashRegressionTest {
    private val source = File(
        "src/main/java/com/luxiaoshi/jianbo/player/PlayerScreen.kt",
    ).readText()

    @Test
    fun orientationHelperNeverCallsItself() {
        val start = source.indexOf("fun applyPlaybackOrientation(video: VideoItem?)")
        val end = source.indexOf("fun showControlsForInteraction()", start)
        require(start >= 0 && end > start)
        val helper = source.substring(start, end)
        assertFalse(helper.contains("applyPlaybackOrientation(video)"))
        assertTrue(helper.contains("targetLandscape = orientationTarget("))
    }

    @Test
    fun everyVideoSelectionUsesTheSharedOrientationHelper() {
        val start = source.indexOf("fun selectVideo(index: Int)")
        val end = source.indexOf("fun playPreviousVideo()", start)
        require(start >= 0 && end > start)
        val block = source.substring(start, end)
        assertTrue(block.contains("applyPlaybackOrientation(video)"))
    }
}
