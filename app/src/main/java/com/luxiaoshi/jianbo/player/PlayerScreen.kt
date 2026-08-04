package com.luxiaoshi.jianbo.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.luxiaoshi.jianbo.data.VideoItem
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private const val SEEK_STEP_MS = 5_000L

private enum class VerticalGestureMode { BRIGHTNESS, VIDEO_SWITCH, VOLUME }

@Composable
fun PlayerScreen(videos: List<VideoItem>, startIndex: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val libVLC = remember {
        LibVLC(
            context,
            arrayListOf(
                "--audio-time-stretch",
                "--no-sub-autodetect-file",
                "--network-caching=300",
            ),
        )
    }
    val player = remember { VlcMediaPlayer(libVLC) }

    val initialIndex = startIndex.coerceIn(videos.indices)
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    var playing by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var speedDialog by remember { mutableStateOf(false) }
    var rotatedQuarterTurn by remember { mutableStateOf(false) }
    var targetLandscape by remember {
        mutableStateOf(naturalLandscape(videos.getOrNull(initialIndex)))
    }
    var overlay by remember { mutableStateOf<String?>(null) }
    var width by remember { mutableIntStateOf(1) }
    var height by remember { mutableIntStateOf(1) }
    var videoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    val isLandscapeScreen = width > height

    fun loadVideo(index: Int) {
        val safeIndex = index.coerceIn(videos.indices)
        val video = videos[safeIndex]
        currentIndex = safeIndex
        rotatedQuarterTurn = false
        targetLandscape = naturalLandscape(video)
        runCatching { player.stop() }
        val media = Media(libVLC, video.uri).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=300")
        }
        player.setMedia(media)
        media.release()
        player.play()
        controlsVisible = true
    }

    fun playPreviousVideo() {
        if (currentIndex > 0) {
            loadVideo(currentIndex - 1)
            overlay = "上一个视频"
        }
    }

    fun playNextVideo() {
        if (currentIndex < videos.lastIndex) {
            loadVideo(currentIndex + 1)
            overlay = "下一个视频"
        }
    }

    BackHandler(onBack = onExit)

    DisposableEffect(player) {
        player.setEventListener { event ->
            when (event.type) {
                VlcMediaPlayer.Event.Playing -> {
                    playing = true
                    runCatching { player.rate = speed }
                }

                VlcMediaPlayer.Event.Paused,
                VlcMediaPlayer.Event.Stopped,
                VlcMediaPlayer.Event.EndReached,
                VlcMediaPlayer.Event.EncounteredError,
                -> {
                    playing = false
                    controlsVisible = true
                }
            }
        }

        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            runCatching { player.stop() }
            runCatching { player.detachViews() }
            player.setEventListener(null)
            player.release()
            libVLC.release()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(videoLayout) {
        val layout = videoLayout
        if (layout != null) {
            player.attachViews(layout, null, true, false)
        }
        onDispose {
            if (layout != null) runCatching { player.detachViews() }
        }
    }

    LaunchedEffect(videoLayout) {
        if (videoLayout != null) loadVideo(currentIndex)
    }

    LaunchedEffect(targetLandscape) {
        activity.requestedOrientation = when (targetLandscape) {
            true -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            false -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(playing, controlsVisible) {
        if (playing && controlsVisible) {
            delay(2500)
            controlsVisible = false
        }
    }

    LaunchedEffect(overlay) {
        if (overlay != null) {
            delay(700)
            overlay = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged {
                width = it.width.coerceAtLeast(1)
                height = it.height.coerceAtLeast(1)
            }
            .pointerInput(width, height, currentIndex) {
                var gestureMode = VerticalGestureMode.VIDEO_SWITCH
                var startBrightness = 0.5f
                var startVolume = 0
                var totalY = 0f

                detectDragGestures(
                    onDragStart = { point ->
                        totalY = 0f
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
                        totalY += dragAmount.y
                        val ratio = (-totalY / height).coerceIn(-1f, 1f)
                        when (gestureMode) {
                            VerticalGestureMode.BRIGHTNESS -> {
                                val value = (startBrightness + ratio).coerceIn(0.01f, 1f)
                                val params = activity.window.attributes
                                params.screenBrightness = value
                                activity.window.attributes = params
                                overlay = "亮度 ${(value * 100).toInt()}%"
                            }

                            VerticalGestureMode.VOLUME -> {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val value = (startVolume + ratio * max).toInt().coerceIn(0, max)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                                overlay = "音量 ${(value * 100f / max).toInt()}%"
                            }

                            VerticalGestureMode.VIDEO_SWITCH -> Unit
                        }
                    },
                    onDragEnd = {
                        if (gestureMode == VerticalGestureMode.VIDEO_SWITCH && abs(totalY) >= height * 0.12f) {
                            if (totalY < 0f) playNextVideo() else playPreviousVideo()
                        }
                    },
                    onDragCancel = { totalY = 0f },
                )
            }
            .pointerInput(playing, controlsVisible, width, height) {
                detectTapGestures(
                    onTap = { point ->
                        val inCenter = point.x in width * 0.25f..width * 0.75f &&
                            point.y in height * 0.20f..height * 0.80f
                        if (inCenter && player.isPlaying) {
                            player.pause()
                            controlsVisible = true
                        } else if (!inCenter) {
                            controlsVisible = !controlsVisible
                        }
                    },
                )
            },
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .rotatePlayerLayout(rotatedQuarterTurn),
            factory = { ctx ->
                VLCVideoLayout(ctx).also { videoLayout = it }
            },
        )

        overlay?.let {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }

        if (!playing) {
            IconButton(
                onClick = {
                    player.play()
                    controlsVisible = true
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(92.dp)
                    .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.extraLarge),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "继续播放",
                    tint = Color.White,
                    modifier = Modifier.size(68.dp),
                )
            }
        }

        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.20f))) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                    }
                    Text(
                        videos.getOrNull(currentIndex)?.name.orEmpty(),
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { speedDialog = true }) {
                        Text("${speedText(speed)}×", color = Color.White)
                    }
                    IconButton(onClick = {
                        rotatedQuarterTurn = !rotatedQuarterTurn
                        targetLandscape = !isLandscapeScreen
                        controlsVisible = true
                    }) {
                        Icon(Icons.Default.Rotate90DegreesCcw, "横竖方向切换", tint = Color.White)
                    }
                }

                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 54.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            player.time = (player.time - SEEK_STEP_MS).coerceAtLeast(0L)
                            controlsVisible = true
                        },
                        modifier = Modifier.size(58.dp),
                    ) {
                        Icon(
                            Icons.Default.FastRewind,
                            "后退五秒",
                            tint = Color.White,
                            modifier = Modifier.size(42.dp),
                        )
                    }

                    IconButton(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            controlsVisible = true
                        },
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "播放或暂停",
                            tint = Color.White,
                            modifier = Modifier.size(56.dp),
                        )
                    }

                    IconButton(
                        onClick = {
                            val duration = player.length.takeIf { it > 0L } ?: Long.MAX_VALUE
                            player.time = (player.time + SEEK_STEP_MS).coerceAtMost(duration)
                            controlsVisible = true
                        },
                        modifier = Modifier.size(58.dp),
                    ) {
                        Icon(
                            Icons.Default.FastForward,
                            "前进五秒",
                            tint = Color.White,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                }

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (isLandscapeScreen) {
                            "左侧调亮度 · 中间上下滑切换视频 · 右侧调音量"
                        } else {
                            "竖向屏上下滑切换视频"
                        },
                        color = Color.White,
                    )
                }
            }
        }
    }

    if (speedDialog) {
        AlertDialog(
            onDismissRequest = { speedDialog = false },
            title = { Text("播放速度") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEEDS.chunked(3).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { value ->
                                Button(
                                    onClick = {
                                        speed = value
                                        runCatching { player.rate = value }
                                        speedDialog = false
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("${speedText(value)}×")
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { speedDialog = false }) { Text("关闭") }
            },
        )
    }
}

private fun naturalLandscape(video: VideoItem?): Boolean? = when {
    video == null -> null
    video.width > 0 && video.height > 0 -> video.width > video.height
    else -> null
}

private fun Modifier.rotatePlayerLayout(rotatedQuarterTurn: Boolean): Modifier {
    if (!rotatedQuarterTurn) return this
    return layout { measurable, constraints ->
        val outerWidth = constraints.maxWidth
        val outerHeight = constraints.maxHeight
        val child = measurable.measure(Constraints.fixed(outerHeight, outerWidth))
        layout(outerWidth, outerHeight) {
            child.placeWithLayer(
                x = (outerWidth - outerHeight) / 2,
                y = (outerHeight - outerWidth) / 2,
            ) {
                rotationZ = 90f
                transformOrigin = TransformOrigin.Center
            }
        }
    }
}

private fun speedText(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
