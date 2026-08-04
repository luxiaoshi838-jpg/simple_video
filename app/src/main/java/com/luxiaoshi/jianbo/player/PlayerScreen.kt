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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.luxiaoshi.jianbo.data.VideoItem
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import kotlin.math.abs

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private const val SEEK_STEP_MS = 5_000L

private val VLC_PREFERRED_EXTENSIONS = setOf(
    "amv", "asf", "avi", "divx", "dv", "flv", "mxf", "ogm", "rm", "rmvb", "vob", "wmv",
)

private enum class PlaybackBackend { MEDIA3, VLC }
private enum class VerticalGestureMode { BRIGHTNESS, VIDEO_SWITCH, VOLUME }

@Composable
fun PlayerScreen(videos: List<VideoItem>, startIndex: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    val libVLC = remember {
        LibVLC(
            context,
            arrayListOf(
                "--audio-time-stretch",
                "--no-sub-autodetect-file",
                "--network-caching=300",
                "--file-caching=300",
            ),
        )
    }
    val vlcPlayer = remember { VlcMediaPlayer(libVLC) }

    val initialIndex = startIndex.coerceIn(videos.indices)
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    var backend by remember { mutableStateOf(preferredBackend(videos.getOrNull(initialIndex))) }
    var playing by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var speedDialog by remember { mutableStateOf(false) }
    var targetLandscape by remember {
        mutableStateOf(naturalLandscape(videos.getOrNull(initialIndex)))
    }
    var overlay by remember { mutableStateOf<String?>(null) }
    var width by remember { mutableIntStateOf(1) }
    var height by remember { mutableIntStateOf(1) }
    var vlcVideoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    var fallbackPositionMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewMs by remember { mutableLongStateOf(0L) }
    val isLandscapeScreen = width > height

    fun selectVideo(index: Int) {
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
        controlsVisible = true
    }

    fun playPreviousVideo() {
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
            controlsVisible = true
            if (!manual) overlay = "已经播放到最后一个视频"
        }
    }

    fun isPlayingNow(): Boolean = when (backend) {
        PlaybackBackend.MEDIA3 -> exoPlayer.isPlaying
        PlaybackBackend.VLC -> vlcPlayer.isPlaying
    }

    fun currentPosition(): Long = when (backend) {
        PlaybackBackend.MEDIA3 -> exoPlayer.currentPosition.coerceAtLeast(0L)
        PlaybackBackend.VLC -> vlcPlayer.time.coerceAtLeast(0L)
    }

    fun currentDuration(): Long = when (backend) {
        PlaybackBackend.MEDIA3 -> exoPlayer.duration.takeIf { it > 0L } ?: 0L
        PlaybackBackend.VLC -> vlcPlayer.length.takeIf { it > 0L } ?: 0L
    }

    fun pausePlayback() {
        when (backend) {
            PlaybackBackend.MEDIA3 -> exoPlayer.pause()
            PlaybackBackend.VLC -> vlcPlayer.pause()
        }
        controlsVisible = true
    }

    fun resumePlayback() {
        when (backend) {
            PlaybackBackend.MEDIA3 -> exoPlayer.play()
            PlaybackBackend.VLC -> vlcPlayer.play()
        }
        controlsVisible = true
    }

    fun seekTo(targetMs: Long) {
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
        controlsVisible = true
    }

    fun seekRelative(deltaMs: Long) {
        seekTo(currentPosition() + deltaMs)
    }

    BackHandler(onBack = onExit)

    DisposableEffect(exoPlayer, backend, currentIndex) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (backend == PlaybackBackend.MEDIA3) playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (backend != PlaybackBackend.MEDIA3) return
                when (playbackState) {
                    Player.STATE_READY -> {
                        val playerDuration = exoPlayer.duration
                        if (playerDuration > 0L) durationMs = playerDuration
                    }

                    Player.STATE_ENDED -> playNextVideo(manual = false)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (backend == PlaybackBackend.MEDIA3) {
                    fallbackPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    playing = false
                    controlsVisible = true
                    overlay = "系统解码失败，正在切换兼容内核"
                    backend = PlaybackBackend.VLC
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(vlcPlayer, backend, speed, currentIndex) {
        vlcPlayer.setEventListener { event ->
            if (backend != PlaybackBackend.VLC) return@setEventListener
            when (event.type) {
                VlcMediaPlayer.Event.Playing -> {
                    playing = true
                    runCatching { vlcPlayer.rate = speed }
                    runCatching { vlcPlayer.setScale(0f) }
                    runCatching { vlcPlayer.setAspectRatio(null) }
                    if (fallbackPositionMs > 0L) {
                        vlcPlayer.time = fallbackPositionMs
                        fallbackPositionMs = 0L
                    }
                }

                VlcMediaPlayer.Event.Paused,
                VlcMediaPlayer.Event.Stopped,
                -> {
                    playing = false
                    controlsVisible = true
                }

                VlcMediaPlayer.Event.EndReached -> playNextVideo(manual = false)

                VlcMediaPlayer.Event.EncounteredError -> {
                    playing = false
                    controlsVisible = true
                    overlay = "该视频无法播放"
                }
            }
        }
        onDispose { vlcPlayer.setEventListener(null) }
    }

    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            runCatching { exoPlayer.stop() }
            exoPlayer.release()
            runCatching { vlcPlayer.stop() }
            runCatching { vlcPlayer.detachViews() }
            vlcPlayer.release()
            libVLC.release()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(vlcVideoLayout, backend) {
        val layout = vlcVideoLayout
        if (backend == PlaybackBackend.VLC && layout != null) {
            runCatching { vlcPlayer.detachViews() }
            vlcPlayer.attachViews(layout, null, true, false)
            runCatching { vlcPlayer.setScale(0f) }
            runCatching { vlcPlayer.setAspectRatio(null) }
        }
        onDispose {
            if (layout != null) runCatching { vlcPlayer.detachViews() }
        }
    }

    LaunchedEffect(currentIndex, backend, vlcVideoLayout) {
        val video = videos.getOrNull(currentIndex) ?: return@LaunchedEffect
        playing = false
        positionMs = 0L
        durationMs = video.durationMs.coerceAtLeast(0L)

        when (backend) {
            PlaybackBackend.MEDIA3 -> {
                runCatching { vlcPlayer.stop() }
                exoPlayer.stop()
                exoPlayer.setMediaItem(MediaItem.fromUri(video.uri))
                exoPlayer.prepare()
                exoPlayer.setPlaybackSpeed(speed)
                exoPlayer.playWhenReady = true
            }

            PlaybackBackend.VLC -> {
                if (vlcVideoLayout == null) return@LaunchedEffect
                exoPlayer.stop()
                runCatching { vlcPlayer.stop() }
                val media = Media(libVLC, video.uri).apply {
                    setHWDecoderEnabled(true, false)
                    addOption(":network-caching=300")
                    addOption(":file-caching=300")
                }
                vlcPlayer.setMedia(media)
                media.release()
                vlcPlayer.play()
            }
        }
    }

    LaunchedEffect(targetLandscape) {
        activity.requestedOrientation = when (targetLandscape) {
            true -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            false -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(currentIndex, backend, playing, isSeeking) {
        while (true) {
            val detectedDuration = currentDuration()
            if (detectedDuration > 0L) durationMs = detectedDuration
            if (!isSeeking) positionMs = currentPosition()
            delay(if (playing) 250L else 500L)
        }
    }

    LaunchedEffect(playing, controlsVisible) {
        if (playing && controlsVisible && !isSeeking) {
            delay(2500)
            controlsVisible = false
        }
    }

    LaunchedEffect(overlay) {
        if (overlay != null) {
            delay(1200)
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
            .pointerInput(width, height, currentIndex, backend) {
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
            .pointerInput(playing, controlsVisible, width, height, backend) {
                detectTapGestures(
                    onTap = { point ->
                        val inCenter = point.x in width * 0.25f..width * 0.75f &&
                            point.y in height * 0.20f..height * 0.80f
                        if (inCenter && isPlayingNow()) {
                            pausePlayback()
                        } else if (!inCenter) {
                            controlsVisible = !controlsVisible
                        }
                    },
                )
            },
    ) {
        when (backend) {
            PlaybackBackend.MEDIA3 -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            player = exoPlayer
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { view ->
                        view.player = exoPlayer
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    },
                )
            }

            PlaybackBackend.VLC -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        VLCVideoLayout(ctx).also { vlcVideoLayout = it }
                    },
                )
            }
        }

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
                onClick = { resumePlayback() },
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
                        targetLandscape = !isLandscapeScreen
                        controlsVisible = true
                    }) {
                        Icon(Icons.Default.Rotate90DegreesCcw, "横竖方向切换", tint = Color.White)
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val shownPosition = if (isSeeking) seekPreviewMs else positionMs
                    val sliderMaximum = durationMs.coerceAtLeast(1L)
                    Slider(
                        value = shownPosition.coerceIn(0L, sliderMaximum).toFloat(),
                        onValueChange = { value ->
                            isSeeking = true
                            seekPreviewMs = value.toLong().coerceIn(0L, sliderMaximum)
                            controlsVisible = true
                        },
                        onValueChangeFinished = {
                            seekTo(seekPreviewMs)
                            isSeeking = false
                        },
                        valueRange = 0f..sliderMaximum.toFloat(),
                        enabled = durationMs > 0L,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatPlaybackTime(shownPosition), color = Color.White)
                        Text(formatPlaybackTime(durationMs), color = Color.White)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { seekRelative(-SEEK_STEP_MS) },
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
                                if (isPlayingNow()) pausePlayback() else resumePlayback()
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
                            onClick = { seekRelative(SEEK_STEP_MS) },
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
                                        when (backend) {
                                            PlaybackBackend.MEDIA3 -> exoPlayer.setPlaybackSpeed(value)
                                            PlaybackBackend.VLC -> runCatching { vlcPlayer.rate = value }
                                        }
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

private fun preferredBackend(video: VideoItem?): PlaybackBackend {
    val extension = video?.name
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    return if (extension in VLC_PREFERRED_EXTENSIONS) PlaybackBackend.VLC
    else PlaybackBackend.MEDIA3
}

private fun naturalLandscape(video: VideoItem?): Boolean? = when {
    video == null -> null
    video.width > 0 && video.height > 0 -> video.width > video.height
    else -> null
}

private fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds / 60L % 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun speedText(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
