package com.luxiaoshi.jianbo.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.weight
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.luxiaoshi.jianbo.data.VideoItem
import kotlinx.coroutines.delay
import kotlin.math.abs

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

@Composable
fun PlayerScreen(videos: List<VideoItem>, startIndex: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val player = remember(videos) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItems(videos.map { video ->
                MediaItem.Builder().setUri(video.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(video.name).build()).build()
            }, startIndex.coerceIn(videos.indices), 0L)
            prepare()
            playWhenReady = true
        }
    }
    var currentIndex by remember { mutableIntStateOf(startIndex.coerceIn(videos.indices)) }
    var playing by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var speedDialog by remember { mutableStateOf(false) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var overlay by remember { mutableStateOf<String?>(null) }
    var width by remember { mutableIntStateOf(1) }
    var height by remember { mutableIntStateOf(1) }

    BackHandler(onBack = onExit)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
            }
        }
        player.addListener(listener)
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            player.removeListener(listener)
            player.release()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(currentIndex) {
        val video = videos.getOrNull(currentIndex)
        activity.requestedOrientation = if (video != null && video.width > video.height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
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
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .onSizeChanged { width = it.width.coerceAtLeast(1); height = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                var startBrightness = 0.5f
                var startVolume = 0
                var onLeft = false
                var totalY = 0f
                detectDragGestures(
                    onDragStart = { p ->
                        onLeft = p.x < width / 2f
                        totalY = 0f
                        startBrightness = activity.window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                        startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        totalY += drag.y
                        val ratio = (-totalY / height).coerceIn(-1f, 1f)
                        if (onLeft) {
                            val value = (startBrightness + ratio).coerceIn(0.01f, 1f)
                            val params = activity.window.attributes
                            params.screenBrightness = value
                            activity.window.attributes = params
                            overlay = "亮度 ${(value * 100).toInt()}%"
                        } else {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val value = (startVolume + ratio * max).toInt().coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                            overlay = "音量 ${(value * 100f / max).toInt()}%"
                        }
                    },
                )
            }
            .pointerInput(playing) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { p ->
                        if (p.x in width * 0.25f..width * 0.75f && p.y in height * 0.2f..height * 0.8f) {
                            if (player.isPlaying) player.pause() else player.play()
                            controlsVisible = true
                        }
                    },
                )
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                rotationZ = rotation
                val reduced = abs(rotation % 180f) == 90f
                scaleX = if (reduced) 0.62f else 1f
                scaleY = if (reduced) 0.62f else 1f
            },
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { it.player = player },
        )

        overlay?.let {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(it, color = Color.White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }

        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))) {
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(12.dp),
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
                        rotation = (rotation + 90f) % 360f
                        controlsVisible = true
                    }) {
                        Icon(Icons.Default.Rotate90DegreesCcw, "翻转视频", tint = Color.White)
                    }
                }
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        enabled = currentIndex > 0,
                        onClick = { player.seekToPreviousMediaItem(); player.play() },
                        modifier = Modifier.size(58.dp),
                    ) {
                        Icon(
                            Icons.Default.FastRewind,
                            "上一个",
                            tint = if (currentIndex > 0) Color.White else Color.Gray,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                    IconButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
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
                        enabled = currentIndex < videos.lastIndex,
                        onClick = { player.seekToNextMediaItem(); player.play() },
                        modifier = Modifier.size(58.dp),
                    ) {
                        Icon(
                            Icons.Default.FastForward,
                            "下一个",
                            tint = if (currentIndex < videos.lastIndex) Color.White else Color.Gray,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("左侧上下滑动调亮度 · 右侧上下滑动调音量 · 中央双击暂停", color = Color.White)
                }
            }
        }
    }

    if (speedDialog) AlertDialog(
        onDismissRequest = { speedDialog = false },
        title = { Text("播放速度") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { value ->
                            Button(
                                onClick = {
                                    speed = value
                                    player.setPlaybackSpeed(value)
                                    speedDialog = false
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("${speedText(value)}×") }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { speedDialog = false }) { Text("关闭") } },
    )
}

private fun speedText(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
