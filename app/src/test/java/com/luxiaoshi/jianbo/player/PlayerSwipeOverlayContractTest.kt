package com.luxiaoshi.jianbo.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSwipeOverlayContractTest {
    private val source = File(
        "src/main/java/com/luxiaoshi/jianbo/player/PlayerScreen.kt",
    ).readText()

    @Test
    fun horizontalSeekNeverWritesCenterOverlay() {
        val start = source.indexOf("DragAxis.HORIZONTAL_SEEK -> {")
        val end = source.indexOf("DragAxis.VERTICAL -> {", start)
        require(start >= 0 && end > start)
        val horizontalBlock = source.substring(start, end)
        assertFalse(horizontalBlock.contains("overlay ="))
    }

    @Test
    fun horizontalGestureClearsAnyExistingOverlay() {
        assertTrue(
            source.contains(
                "if (gestureAxis == DragAxis.HORIZONTAL_SEEK) {\n                                overlay = null",
            ),
        )
    }
}
