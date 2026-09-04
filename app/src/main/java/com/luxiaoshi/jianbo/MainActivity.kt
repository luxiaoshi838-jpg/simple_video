package com.luxiaoshi.jianbo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luxiaoshi.jianbo.data.LibraryUiState
import com.luxiaoshi.jianbo.data.VideoGroup
import com.luxiaoshi.jianbo.data.VideoItem
import com.luxiaoshi.jianbo.data.VideoThumbnailCache
import com.luxiaoshi.jianbo.player.PlayerScreen
import com.luxiaoshi.jianbo.ui.theme.JianboTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { JianboTheme { JianboApp(viewModel) } }
    }
}

private data class Playback(val videos: List<VideoItem>, val index: Int)

@Composable
private fun JianboApp(viewModel: LibraryViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var openedGroupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var returnVideoIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var playback by remember { mutableStateOf<Playback?>(null) }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setAccess(granted, hiddenScanAccessGranted(granted))
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::importFolder)
    }
    val hiddenScanAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.setAccess(state.permissionGranted, hiddenScanAccessGranted(state.permissionGranted))
    }

    LaunchedEffect(Unit) {
        val mediaGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        viewModel.setAccess(mediaGranted, hiddenScanAccessGranted(mediaGranted))
    }

    playback?.let {
        PlayerScreen(
            videos = it.videos,
            startIndex = it.index,
            onExit = { playback = null },
        )
        return
    }

    val group = state.groups.firstOrNull { it.key == openedGroupKey }
    if (openedGroupKey != null && group == null) {
        openedGroupKey = null
        returnVideoIndex = null
    }

    if (group != null) {
        GroupScreen(
            group = group,
            focusIndex = returnVideoIndex,
            onBack = {
                openedGroupKey = null
                returnVideoIndex = null
            },
            play = { index ->
                returnVideoIndex = index
                playback = Playback(group.videos, index)
            },
        )
    } else {
        LibraryScreen(
            state = state,
            requestPermission = { permissionLauncher.launch(permission) },
            requestHiddenScanAccess = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val appSettingsIntent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { hiddenScanAccessLauncher.launch(appSettingsIntent) }
                        .onFailure {
                            hiddenScanAccessLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            )
                        }
                } else {
                    permissionLauncher.launch(permission)
                }
            },
            importFolder = { folderLauncher.launch(null) },
            refresh = viewModel::refresh,
            hideGroups = viewModel::hideGroups,
            restoreGroups = viewModel::restoreHiddenGroups,
            openGroup = {
                openedGroupKey = it.key
                returnVideoIndex = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun LibraryScreen(
    state: LibraryUiState,
    requestPermission: () -> Unit,
    requestHiddenScanAccess: () -> Unit,
    importFolder: () -> Unit,
    refresh: () -> Unit,
    hideGroups: (Set<String>) -> Unit,
    restoreGroups: () -> Unit,
    openGroup: (VideoGroup) -> Unit,
) {
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmHide by remember { mutableStateOf(false) }
    BackHandler(selecting) {
        selecting = false
        selected = emptySet()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (selecting) "已选择 ${selected.size} 个分组" else "简播") },
            navigationIcon = {
                if (selecting) {
                    IconButton(onClick = {
                        selecting = false
                        selected = emptySet()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "退出批量管理")
                    }
                }
            },
            actions = {
                if (selecting) {
                    IconButton(onClick = { selected = state.groups.map { it.key }.toSet() }) {
                        Icon(Icons.Default.SelectAll, "全选")
                    }
                    IconButton(onClick = { if (selected.isNotEmpty()) confirmHide = true }) {
                        Icon(Icons.Default.DeleteOutline, "从简播移除")
                    }
                } else {
                    IconButton(onClick = refresh) { Icon(Icons.Default.Refresh, "刷新") }
                    if (state.hiddenGroupCount > 0) {
                        IconButton(onClick = restoreGroups) {
                            Icon(Icons.Default.Restore, "恢复隐藏分组")
                        }
                    }
                }
            },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = importFolder, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.size(8.dp))
                            Text("手动导入文件夹")
                        }
                        if (!state.permissionGranted) {
                            FilledTonalButton(onClick = requestPermission) {
                                Text("授权自动扫描")
                            }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !state.hiddenScanAccessGranted) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = requestHiddenScanAccess,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.FolderOpen, null)
                                Spacer(Modifier.size(8.dp))
                                Text("授权微信隐藏视频扫描")
                            }
                            Text(
                                "用于扫描共享存储中未进入系统媒体库的微信视频，包括 . 开头的隐藏目录。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (state.groups.isEmpty() && !state.isLoading) {
                    item { EmptyLibrary(state.permissionGranted, requestPermission, importFolder) }
                }
                itemsIndexed(state.groups, key = { _, item -> item.key }) { _, group ->
                    val checked = group.key in selected
                    Card(
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = {
                                if (selecting) selected = selected.toggle(group.key)
                                else openGroup(group)
                            },
                            onLongClick = {
                                selecting = true
                                selected = selected + group.key
                            },
                        ),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (checked) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (checked) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    null,
                                    Modifier.size(40.dp),
                                )
                            } else {
                                Icon(
                                    Icons.Default.Folder,
                                    null,
                                    Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    group.name,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${group.videos.size} 个视频 · ${if (group.source == VideoGroup.Source.MANUAL) "手动导入" else "自动扫描"}",
                                )
                            }
                        }
                    }
                }
            }
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }

    if (confirmHide) {
        AlertDialog(
            onDismissRequest = { confirmHide = false },
            title = { Text("从简播移除分组？") },
            text = { Text("只隐藏所选分组，不会删除手机中的视频文件。以后可用右上角恢复按钮重新显示。") },
            confirmButton = {
                TextButton(onClick = {
                    hideGroups(selected)
                    selected = emptySet()
                    selecting = false
                    confirmHide = false
                }) {
                    Text("仅移除分组")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmHide = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupScreen(
    group: VideoGroup,
    focusIndex: Int?,
    onBack: () -> Unit,
    play: (Int) -> Unit,
) {
    val safeFocusIndex = focusIndex?.coerceIn(group.videos.indices)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = safeFocusIndex ?: 0,
    )

    LaunchedEffect(group.key, safeFocusIndex) {
        safeFocusIndex?.let { listState.scrollToItem(it) }
    }

    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = if (group.location.isNullOrBlank()) {
                            "${group.videos.size} 个视频"
                        } else {
                            "${group.videos.size} 个视频 · ${group.location}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
        )
    }) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(10.dp),
        ) {
            itemsIndexed(group.videos, key = { _, video -> video.id }) { index, video ->
                Card(
                    onClick = { play(index) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (index == safeFocusIndex) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VideoThumbnail(video)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                video.name,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatDuration(video.durationMs),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Icon(Icons.Default.PlayCircle, "播放")
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoThumbnail(video: VideoItem) {
    val context = LocalContext.current
    val frame by produceState<Bitmap?>(
        initialValue = VideoThumbnailCache.peek(video),
        key1 = video.id,
        key2 = video.dateAddedSeconds,
        key3 = video.durationMs,
    ) {
        value = VideoThumbnailCache.load(context.applicationContext, video)
    }

    Box(
        modifier = Modifier
            .size(width = 112.dp, height = 64.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null) {
            Image(
                bitmap = requireNotNull(frame).asImageBitmap(),
                contentDescription = "${video.name}预览图",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Default.VideoFile, null, Modifier.size(34.dp))
        }
    }
}

@Composable
private fun EmptyLibrary(
    granted: Boolean,
    request: () -> Unit,
    importFolder: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 50.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.VideoFile, null, Modifier.size(68.dp))
        Text("还没有找到视频", style = MaterialTheme.typography.headlineSmall)
        Text(if (granted) "可手动导入文件夹，或刷新媒体库。" else "授权后自动扫描，也可只使用手动导入。")
        if (!granted) Button(onClick = request) { Text("授权自动扫描") }
        FilledTonalButton(onClick = importFolder) { Text("手动导入文件夹") }
    }
}

private fun hiddenScanAccessGranted(mediaPermissionGranted: Boolean): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        mediaPermissionGranted
    }

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "时长未知"
    val seconds = ms / 1000
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}
