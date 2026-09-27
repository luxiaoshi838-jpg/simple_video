package com.luxiaoshi.jianbo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarSettingsContractTest {
    private val main = File("src/main/java/com/luxiaoshi/jianbo/MainActivity.kt").readText()
    private val player = File("src/main/java/com/luxiaoshi/jianbo/player/PlayerScreen.kt").readText()

    @Test
    fun topBarKeepsRequiredSortOrientationRefreshOrder() {
        val sort = main.indexOf("IconButton(onClick = toggleFolderSort)")
        val orientation = main.indexOf("IconButton(onClick = cyclePlaybackOrientation)")
        val refresh = main.indexOf("IconButton(onClick = refresh)")
        assertTrue(sort >= 0)
        assertTrue(orientation > sort)
        assertTrue(refresh > orientation)
    }

    @Test
    fun orientationButtonHasThreeDistinctVisualStates() {
        assertTrue(main.contains("PlaybackOrientationMode.ADAPTIVE -> Icons.Default.ScreenRotation"))
        assertTrue(main.contains("PlaybackOrientationMode.PORTRAIT -> Icons.Default.PhoneAndroid"))
        assertTrue(main.contains("PlaybackOrientationMode.LANDSCAPE -> Icons.Default.PhoneAndroid"))
        assertTrue(main.contains("Modifier.rotate(90f)"))
    }

    @Test
    fun fixedOrientationDoesNotGetOverwrittenByDetectedVideoSize() {
        assertTrue(
            player.contains(
                "orientationMode == PlaybackOrientationMode.ADAPTIVE &&",
            ),
        )
        assertTrue(player.contains("orientationTarget(orientationMode, naturalLandscape(video))"))
    }
}
