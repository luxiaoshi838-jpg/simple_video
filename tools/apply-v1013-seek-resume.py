from pathlib import Path
import subprocess

ROOT = Path(".")
PLAYER = ROOT / "app/src/main/java/com/luxiaoshi/jianbo/player/PlayerScreen.kt"
GRADLE = ROOT / "app/build.gradle.kts"
ANDROID_WF = ROOT / ".github/workflows/android.yml"
PATCH = ROOT / "tools/v108-controls.patch"

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)

# Materialize the tracked 1.0.8 controls patch first so 1.0.13 source is standalone.
player = PLAYER.read_text(encoding="utf-8")
if "CONTROLS_HIDE_DELAY_MS = 5_000L" not in player:
    subprocess.run(["git", "apply", "--recount", "--check", str(PATCH)], check=True)
    subprocess.run(["git", "apply", "--recount", str(PATCH)], check=True)
    player = PLAYER.read_text(encoding="utf-8")

player = replace_once(
    player,
    "private enum class PlaybackBackend { MEDIA3, VLC }\nprivate enum class VerticalGestureMode { BRIGHTNESS, VIDEO_SWITCH, VOLUME }",
    "private enum class PlaybackBackend { MEDIA3, VLC }\nprivate enum class VerticalGestureMode { BRIGHTNESS, VIDEO_SWITCH, VOLUME }\nprivate enum class DragAxis { UNDECIDED, HORIZONTAL_SEEK, VERTICAL }",
    "drag axis enum",
)
player = replace_once(
    player,
    "    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }\n\n    val exoPlayer = remember {",
    "    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }\n    val progressStore = remember { PlaybackProgressStore(context.applicationContext) }\n\n    val exoPlayer = remember {",
    "progress store",
)
player = replace_once(
    player,
    "    val initialIndex = startIndex.coerceIn(videos.indices)\n    var currentIndex by remember { mutableIntStateOf(initialIndex) }",
    "    val initialIndex = startIndex.coerceIn(videos.indices)\n    val initialVideo = videos[initialIndex]\n    var currentIndex by remember { mutableIntStateOf(initialIndex) }",
    "initial video",
)
player = replace_once(
    player,
    "    var fallbackPositionMs by remember { mutableLongStateOf(0L) }\n    var positionMs by remember { mutableLongStateOf(0L) }\n    var durationMs by remember { mutableLongStateOf(0L) }\n    var isSeeking by remember { mutableStateOf(false) }\n    var seekPreviewMs by remember { mutableLongStateOf(0L) }",
    """    var fallbackPositionMs by remember { mutableLongStateOf(0L) }
    var resumePositionMs by remember {
        mutableLongStateOf(progressStore.loadResumePosition(initialVideo))
    }
    var currentVideoCompleted by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(resumePositionMs) }
    var durationMs by remember { mutableLongStateOf(initialVideo.durationMs.coerceAtLeast(0L)) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewMs by remember { mutableLongStateOf(resumePositionMs) }""",
    "resume state",
)
player = replace_once(
    player,
    """    fun showControlsForInteraction() {
        controlsVisible = true
        controlsInteractionTick += 1L
    }

    fun selectVideo(index: Int) {""",
    """    fun showControlsForInteraction() {
        controlsVisible = true
        controlsInteractionTick += 1L
    }

    fun saveCurrentProgress() {
        val video = videos.getOrNull(currentIndex) ?: return
        if (currentVideoCompleted) {
            progressStore.clear(video)
            return
        }
        val enginePosition = when (backend) {
            PlaybackBackend.MEDIA3 -> exoPlayer.currentPosition.coerceAtLeast(0L)
            PlaybackBackend.VLC -> vlcPlayer.time.coerceAtLeast(0L)
        }
        val current = if (enginePosition > 0L) enginePosition else positionMs.coerceAtLeast(0L)
        if (current > 0L) progressStore.save(video, current) else progressStore.clear(video)
    }

    fun clearCurrentProgress(markCompleted: Boolean = false) {
        videos.getOrNull(currentIndex)?.let(progressStore::clear)
        if (markCompleted) {
            currentVideoCompleted = true
            positionMs = 0L
            seekPreviewMs = 0L
        }
    }

    fun exitPlayer() {
        saveCurrentProgress()
        onExit()
    }

    fun selectVideo(index: Int) {""",
    "progress helpers",
)
player = replace_once(
    player,
    """    fun selectVideo(index: Int) {
        val safeIndex = index.coerceIn(videos.indices)
        val video = videos[safeIndex]
        currentIndex = safeIndex
        backend = preferredBackend(video)
        fallbackPositionMs = 0L
        positionMs = 0L
        durationMs = video.durationMs.coerceAtLeast(0L)
        isSeeking = false
        targetLandscape = naturalLandscape(video)
        playing = false
        showControlsForInteraction()
    }""",
    """    fun selectVideo(index: Int) {
        val safeIndex = index.coerceIn(videos.indices)
        val video = videos[safeIndex]
        currentIndex = safeIndex
        backend = preferredBackend(video)
        currentVideoCompleted = false
        fallbackPositionMs = 0L
        resumePositionMs = progressStore.loadResumePosition(video)
        positionMs = resumePositionMs
        seekPreviewMs = resumePositionMs
        durationMs = video.durationMs.coerceAtLeast(0L)
        isSeeking = false
        targetLandscape = naturalLandscape(video)
        playing = false
        showControlsForInteraction()
    }""",
    "select video",
)
player = replace_once(
    player,
    """    fun playPreviousVideo() {
        if (currentIndex > 0) {
            selectVideo(currentIndex - 1)
            overlay = "上一个视频"
        }
    }

    fun playNextVideo(manual: Boolean = true) {
        if (currentIndex < videos.lastIndex) {
            selectVideo(currentIndex + 1)
            overlay = if (manual) "下一个视频" else "自动播放下一个"
        } else {
            playing = false
            showControlsForInteraction()
            if (!manual) overlay = "已经播放到最后一个视频"
        }
    }""",
    """    fun playPreviousVideo() {
        if (currentIndex > 0) {
            saveCurrentProgress()
            selectVideo(currentIndex - 1)
            overlay = "上一个视频"
        }
    }

    fun playNextVideo(manual: Boolean = true) {
        if (manual) saveCurrentProgress() else clearCurrentProgress(markCompleted = true)
        if (currentIndex < videos.lastIndex) {
            selectVideo(currentIndex + 1)
            overlay = if (manual) "下一个视频" else "自动播放下一个"
        } else {
            playing = false
            showControlsForInteraction()
            if (!manual) overlay = "已经播放到最后一个视频"
        }
    }""",
    "next previous",
)
player = replace_once(
    player,
    """    fun seekTo(targetMs: Long) {
        val knownDuration = currentDuration().takeIf { it > 0L } ?: durationMs
        val safeTarget = if (knownDuration > 0L) {
            targetMs.coerceIn(0L, knownDuration)
        } else {
            targetMs.coerceAtLeast(0L)
        }
        when (backend) {
            PlaybackBackend.MEDIA3 -> exoPlayer.seekTo(safeTarget)
            PlaybackBackend.VLC -> vlcPlayer.time = safeTarget
        }
        positionMs = safeTarget
        showControlsForInteraction()
    }""",
    """    fun seekTo(targetMs: Long) {
        val knownDuration = currentDuration().takeIf { it > 0L } ?: durationMs
        val safeTarget = if (knownDuration > 0L) {
            targetMs.coerceIn(0L, knownDuration)
        } else {
            targetMs.coerceAtLeast(0L)
        }
        when (backend) {
            PlaybackBackend.MEDIA3 -> exoPlayer.seekTo(safeTarget)
            PlaybackBackend.VLC -> vlcPlayer.time = safeTarget
        }
        positionMs = safeTarget
        seekPreviewMs = safeTarget
        currentVideoCompleted = knownDuration > 0L && safeTarget >= knownDuration
        videos.getOrNull(currentIndex)?.let { video ->
            if (currentVideoCompleted || safeTarget <= 0L) progressStore.clear(video)
            else progressStore.save(video, safeTarget)
        }
        showControlsForInteraction()
    }""",
    "seek save",
)
player = replace_once(player, "    BackHandler(onBack = onExit)", "    BackHandler(onBack = { exitPlayer() })", "back handler")
player = replace_once(
    player,
    "                Lifecycle.Event.ON_STOP -> {\n                    resumeAfterForeground = isPlayingNow()",
    "                Lifecycle.Event.ON_STOP -> {\n                    saveCurrentProgress()\n                    resumeAfterForeground = isPlayingNow()",
    "lifecycle save",
)
player = replace_once(
    player,
    """                    Player.STATE_READY -> {
                        val playerDuration = exoPlayer.duration
                        if (playerDuration > 0L) durationMs = playerDuration
                    }""",
    """                    Player.STATE_READY -> {
                        val playerDuration = exoPlayer.duration
                        if (playerDuration > 0L) {
                            durationMs = playerDuration
                            if (positionMs > 0L && positionMs >= playerDuration) {
                                videos.getOrNull(currentIndex)?.let(progressStore::clear)
                                exoPlayer.seekTo(0L)
                                positionMs = 0L
                                seekPreviewMs = 0L
                                currentVideoCompleted = false
                            }
                        }
                    }""",
    "ready state",
)
player = replace_once(
    player,
    """                    if (fallbackPositionMs > 0L) {
                        vlcPlayer.time = fallbackPositionMs
                        fallbackPositionMs = 0L
                    }""",
    """                    if (fallbackPositionMs > 0L) {
                        val target = fallbackPositionMs
                        vlcPlayer.time = target
                        positionMs = target
                        seekPreviewMs = target
                        fallbackPositionMs = 0L
                    }""",
    "vlc fallback position",
)
player = replace_once(
    player,
    "        onDispose {\n            runCatching { exoPlayer.stop() }",
    "        onDispose {\n            saveCurrentProgress()\n            runCatching { exoPlayer.stop() }",
    "dispose save",
)
player = replace_once(
    player,
    "        playing = false\n        positionMs = 0L\n        durationMs = video.durationMs.coerceAtLeast(0L)",
    "        playing = false\n        positionMs = resumePositionMs\n        seekPreviewMs = resumePositionMs\n        durationMs = video.durationMs.coerceAtLeast(0L)",
    "load resume state",
)
player = replace_once(
    player,
    """                exoPlayer.stop()
                exoPlayer.setMediaItem(MediaItem.fromUri(video.uri))
                exoPlayer.prepare()""",
    """                exoPlayer.stop()
                val mediaItem = MediaItem.fromUri(video.uri)
                if (resumePositionMs > 0L) {
                    exoPlayer.setMediaItem(mediaItem, resumePositionMs)
                } else {
                    exoPlayer.setMediaItem(mediaItem)
                }
                resumePositionMs = 0L
                exoPlayer.prepare()""",
    "media3 resume",
)
player = replace_once(
    player,
    """                vlcPlayer.setMedia(media)
                media.release()
                vlcPlayer.play()""",
    """                vlcPlayer.setMedia(media)
                media.release()
                if (fallbackPositionMs <= 0L && resumePositionMs > 0L) {
                    fallbackPositionMs = resumePositionMs
                }
                resumePositionMs = 0L
                vlcPlayer.play()""",
    "vlc resume",
)
player = replace_once(
    player,
    "    LaunchedEffect(controlsVisible, controlsInteractionTick, isSeeking, speedDialog) {",
    """    LaunchedEffect(currentIndex, backend, playing, isSeeking, currentVideoCompleted) {
        if (playing && !isSeeking && !currentVideoCompleted) {
            while (true) {
                delay(2_000L)
                saveCurrentProgress()
            }
        }
    }

    LaunchedEffect(controlsVisible, controlsInteractionTick, isSeeking, speedDialog) {""",
    "periodic save",
)

start_marker = "            .pointerInput(width, height, currentIndex, backend) {"
end_marker = "            .pointerInput(playing, controlsVisible, width, height, backend) {"
start = player.index(start_marker)
end = player.index(end_marker, start)
gesture = """            .pointerInput(width, height, currentIndex, backend) {
                var gestureAxis = DragAxis.UNDECIDED
                var gestureMode = VerticalGestureMode.VIDEO_SWITCH
                var startBrightness = 0.5f
                var startVolume = 0
                var totalX = 0f
                var totalY = 0f
                var seekStartMs = 0L
                var gestureDurationMs = 0L

                detectDragGestures(
                    onDragStart = { point ->
                        showControlsForInteraction()
                        gestureAxis = DragAxis.UNDECIDED
                        totalX = 0f
                        totalY = 0f
                        seekStartMs = currentPosition()
                        gestureDurationMs = currentDuration().takeIf { it > 0L } ?: durationMs
                        gestureMode = if (!isLandscapeScreen) {
                            VerticalGestureMode.VIDEO_SWITCH
                        } else {
                            when {
                                point.x < width * 0.30f -> VerticalGestureMode.BRIGHTNESS
                                point.x > width * 0.70f -> VerticalGestureMode.VOLUME
                                else -> VerticalGestureMode.VIDEO_SWITCH
                            }
                        }
                        startBrightness = activity.window.attributes.screenBrightness
                            .takeIf { it >= 0f }
                            ?: 0.5f
                        startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        showControlsForInteraction()
                        totalX += dragAmount.x
                        totalY += dragAmount.y
                        if (gestureAxis == DragAxis.UNDECIDED) {
                            gestureAxis = if (abs(totalX) >= abs(totalY)) {
                                DragAxis.HORIZONTAL_SEEK
                            } else {
                                DragAxis.VERTICAL
                            }
                        }
                        when (gestureAxis) {
                            DragAxis.HORIZONTAL_SEEK -> {
                                if (gestureDurationMs > 0L) {
                                    isSeeking = true
                                    seekPreviewMs = seekPositionForHorizontalDrag(
                                        startPositionMs = seekStartMs,
                                        durationMs = gestureDurationMs,
                                        dragPx = totalX,
                                        screenWidthPx = width.toFloat(),
                                    )
                                    overlay =
                                        "\${formatPlaybackTime(seekPreviewMs)} / \${formatPlaybackTime(gestureDurationMs)}"
                                }
                            }
                            DragAxis.VERTICAL -> {
                                val ratio = (-totalY / height).coerceIn(-1f, 1f)
                                when (gestureMode) {
                                    VerticalGestureMode.BRIGHTNESS -> {
                                        val value = (startBrightness + ratio).coerceIn(0.01f, 1f)
                                        val params = activity.window.attributes
                                        params.screenBrightness = value
                                        activity.window.attributes = params
                                        overlay = "亮度 \${(value * 100).toInt()}%"
                                    }
                                    VerticalGestureMode.VOLUME -> {
                                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        val value = (startVolume + ratio * max).toInt().coerceIn(0, max)
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                                        overlay = "音量 \${(value * 100f / max).toInt()}%"
                                    }
                                    VerticalGestureMode.VIDEO_SWITCH -> Unit
                                }
                            }
                            DragAxis.UNDECIDED -> Unit
                        }
                    },
                    onDragEnd = {
                        when (gestureAxis) {
                            DragAxis.HORIZONTAL_SEEK -> {
                                if (isSeeking) {
                                    seekTo(seekPreviewMs)
                                    isSeeking = false
                                }
                            }
                            DragAxis.VERTICAL -> {
                                if (
                                    gestureMode == VerticalGestureMode.VIDEO_SWITCH &&
                                    abs(totalY) >= height * 0.12f
                                ) {
                                    if (totalY < 0f) playNextVideo() else playPreviousVideo()
                                }
                            }
                            DragAxis.UNDECIDED -> Unit
                        }
                    },
                    onDragCancel = {
                        isSeeking = false
                        seekPreviewMs = positionMs
                        totalX = 0f
                        totalY = 0f
                    },
                )
            }
"""
player = player[:start] + gesture + player[end:]
player = replace_once(player, "                    IconButton(onClick = onExit) {", "                    IconButton(onClick = { exitPlayer() }) {", "top back")
player = replace_once(
    player,
    "private fun preferredBackend(video: VideoItem?): PlaybackBackend {",
    """internal fun seekPositionForHorizontalDrag(
    startPositionMs: Long,
    durationMs: Long,
    dragPx: Float,
    screenWidthPx: Float,
): Long {
    if (durationMs <= 0L || screenWidthPx <= 0f) return startPositionMs.coerceAtLeast(0L)
    val safeStart = startPositionMs.coerceIn(0L, durationMs)
    val deltaMs = (durationMs.toDouble() * (dragPx / screenWidthPx).toDouble()).toLong()
    return (safeStart + deltaMs).coerceIn(0L, durationMs)
}

internal fun normalizeResumePosition(savedPositionMs: Long, durationMs: Long): Long {
    if (savedPositionMs <= 0L) return 0L
    if (durationMs > 0L && savedPositionMs >= durationMs) return 0L
    return savedPositionMs
}

private fun preferredBackend(video: VideoItem?): PlaybackBackend {""",
    "pure helpers",
)
PLAYER.write_text(player, encoding="utf-8")

progress_store = """package com.luxiaoshi.jianbo.player

import android.content.Context
import com.luxiaoshi.jianbo.data.VideoItem

internal class PlaybackProgressStore(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadResumePosition(video: VideoItem): Long {
        val stored = preferences.getLong(key(video), 0L).coerceAtLeast(0L)
        val normalized = normalizeResumePosition(stored, video.durationMs)
        if (stored > 0L && normalized == 0L && video.durationMs > 0L) clear(video)
        return normalized
    }

    fun save(video: VideoItem, positionMs: Long) {
        val safePosition = positionMs.coerceAtLeast(0L)
        val editor = preferences.edit()
        if (safePosition > 0L) editor.putLong(key(video), safePosition)
        else editor.remove(key(video))
        editor.apply()
    }

    fun clear(video: VideoItem) {
        preferences.edit().remove(key(video)).apply()
    }

    private fun key(video: VideoItem): String {
        val identity = video.storageIdentity
            ?.takeIf { it.isNotBlank() }
            ?: video.uri.toString().takeIf { it.isNotBlank() }
            ?: video.id
        return "video:$identity"
    }

    private companion object {
        const val PREFERENCES_NAME = "jianbo_playback_progress"
    }
}
"""
store_path = ROOT / "app/src/main/java/com/luxiaoshi/jianbo/player/PlaybackProgressStore.kt"
store_path.write_text(progress_store, encoding="utf-8")

tests = """package com.luxiaoshi.jianbo.player

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
"""
test_path = ROOT / "app/src/test/java/com/luxiaoshi/jianbo/player/PlayerInteractionLogicTest.kt"
test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text(tests, encoding="utf-8")

gradle = GRADLE.read_text(encoding="utf-8")
gradle = replace_once(
    gradle,
    '        versionCode = 13\n        versionName = "1.0.12"',
    '        versionCode = 14\n        versionName = "1.0.13"',
    "version bump",
)
GRADLE.write_text(gradle, encoding="utf-8")

workflow = ANDROID_WF.read_text(encoding="utf-8")
workflow = replace_once(
    workflow,
    """      - name: Apply tracked source patches
        run: |
          if [ -f tools/v108-controls.patch ]; then
            git apply --recount --check tools/v108-controls.patch
            git apply --recount tools/v108-controls.patch
          fi

""",
    "",
    "remove legacy patch step",
)
workflow = replace_once(
    workflow,
    """      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

""",
    "",
    "remove broken setup-android action",
)
workflow = replace_once(
    workflow,
    "    runs-on: ubuntu-latest\n    timeout-minutes: 20",
    """    runs-on: ubuntu-latest
    timeout-minutes: 20
    env:
      ANDROID_HOME: /home/runner/android-sdk
      ANDROID_SDK_ROOT: /home/runner/android-sdk""",
    "set deterministic Android SDK home",
)
workflow = replace_once(
    workflow,
    """      - name: Install Android API 36
        run: sdkmanager "platforms;android-36" "build-tools;36.0.0"
""",
    """      - name: Install Android SDK and API 36
        run: |
          mkdir -p "$ANDROID_HOME/cmdline-tools"
          curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o /tmp/android-commandline.zip
          rm -rf /tmp/android-commandline
          mkdir -p /tmp/android-commandline
          unzip -q /tmp/android-commandline.zip -d /tmp/android-commandline
          mv /tmp/android-commandline/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
          yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
          "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-36" "build-tools;36.0.0"
""",
    "bootstrap Android SDK",
)
workflow = replace_once(
    workflow,
    "      - name: Build unsigned release APK\n        run: gradle :app:assembleRelease --stacktrace",
    """      - name: Run unit tests
        run: gradle :app:testDebugUnitTest :app:testReleaseUnitTest --stacktrace

      - name: Build unsigned release APK
        run: gradle :app:assembleRelease --stacktrace""",
    "unit test step",
)
ANDROID_WF.write_text(workflow, encoding="utf-8")

release = """# 简播 1.0.13

日期：2026-09-21

## 新增
- 播放画面支持整屏横向拖动调节播放进度：向右前进、向左后退。
- 拖动距离按当前屏幕宽度占比与视频总时长连续换算，竖屏、横屏、反向横屏均按当前画面宽度自动适配。
- 拖动中显示目标时间预览，松手后一次性 seek。
- 每个视频持久保存上次播放位置，再次进入时从该位置继续播放。
- 手动退出、切换上一部/下一部、播放过程中都会保存当前位置。
- 视频自然播放结束并自动跳入下一部时，清除已播放完视频的断点，再次进入从 0 开始。

## 保持不变
- 原竖向手势、底部进度条、前后 5 秒、倍速、暂停和控制栏自动隐藏。
- Media3 主播放内核与 VLC 兼容回退。
- applicationId = com.luxiaoshi.jianbo。

## 版本
- versionName = 1.0.13
- versionCode = 14
"""
(ROOT / "RELEASE_1.0.13.md").write_text(release, encoding="utf-8")

# The old patch is now materialized in source; remove it to avoid double-application.
if PATCH.exists():
    PATCH.unlink()

# The one-shot workflow is only a migration vehicle; remove it from the final source commit.
one_shot = ROOT / ".github/workflows/build-v1013-seek-resume.yml"
if one_shot.exists():
    one_shot.unlink()

print("v1.0.13 source migration complete")
