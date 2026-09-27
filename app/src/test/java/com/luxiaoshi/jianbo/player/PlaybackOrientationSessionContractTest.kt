package com.luxiaoshi.jianbo.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOrientationSessionContractTest {
    private val source = File(
        "src/main/java/com/luxiaoshi/jianbo/player/PlayerScreen.kt",
    ).readText()

    @Test
    fun everyVideoLoadReappliesTheSelectedToolbarOrientationPolicy() {
        assertTrue(source.contains("fun applyPlaybackOrientation(video: VideoItem?)"))
        assertTrue(
            source.contains(
                "LaunchedEffect(currentIndex, backend, vlcVideoLayout, orientationMode)",
            ),
        )
        val loadStart = source.indexOf(
            "LaunchedEffect(currentIndex, backend, vlcVideoLayout, orientationMode)",
        )
        val loadEnd = source.indexOf(
            "LaunchedEffect(targetLandscape)",
            loadStart,
        )
        require(loadStart >= 0 && loadEnd > loadStart)
        val loadBlock = source.substring(loadStart, loadEnd)
        assertTrue(loadBlock.contains("applyPlaybackOrientation(video)"))
    }

    @Test
    fun autoAdvanceAndManualSwitchShareTheSamePerVideoSelectionPath() {
        assertTrue(source.contains("Player.STATE_ENDED -> playNextVideo(manual = false)"))
        assertTrue(source.contains("VlcMediaPlayer.Event.EndReached -> playNextVideo(manual = false)"))
        assertTrue(source.contains("selectVideo(currentIndex + 1)"))
        assertTrue(source.contains("selectVideo(currentIndex - 1)"))
        assertTrue(source.contains("applyPlaybackOrientation(video)"))
    }

    @Test
    fun bothPlaybackBackendsPreserveVideoAspectRatioAfterRotation() {
        assertTrue(
            source.countOccurrences("AspectRatioFrameLayout.RESIZE_MODE_FIT") >= 2,
        )
        assertTrue(source.contains("vlcPlayer.setScale(0f)"))
        assertTrue(source.contains("vlcPlayer.setAspectRatio(null)"))
    }

    private fun String.countOccurrences(token: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = indexOf(token, index)
            if (index < 0) return count
            count++
            index += token.length
        }
    }
}
