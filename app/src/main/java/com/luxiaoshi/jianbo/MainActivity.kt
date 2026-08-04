package com.luxiaoshi.jianbo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luxiaoshi.jianbo.data.LibraryUiState
import com.luxiaoshi.jianbo.data.VideoGroup
import com.luxiaoshi.jianbo.data.VideoItem
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
    var playback by remember { mutableStateOf<Playback?>(null) }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.setPermission(it)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::importFolder)
    }

    LaunchedEffect(Unit) {
        viewModel.setPermission(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        )
    }

    playback?.let {
        PlayerScreen(it.videos, it.index, onExit = { playback = null })
        return
    }
    val group = state.groups.firstOrNull { it.key == openedGroupKey }
    if (openedGroupKey != null && group == null) openedGroupKey = null
    if (group != null) {
        GroupScreen(group, { openedGroupKey = null }) { index -> playback = Playback(group.videos, index) }
    } else {
        LibraryScreen(
            state = state,
            requestPermission = { permissionLauncher.launch(permission) },
            importFolder = { folderLauncher.launch(null) },
            refresh = viewModel::refresh,
            hideGroups = viewModel::hideGroups,
            restoreGroups = viewModel::restoreHiddenGroups,
            openGroup = { openedGroupKey = it.key },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun LibraryScreen(
    state: LibraryUiState,
    requestPermission: () -> Unit,
    importFolder: () -> Unit,
    refresh: () -> Unit,
    hideGroups: (Set<String>) -> Unit,
    restoreGroups: () -> Unit,
    openGroup: (VideoGroup) -> Unit,
) {
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmHide by remember { mutableStateOf(false) }
    BackHandler(selecting) { selecting = false; selected = emptySet() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (selecting) "已选择 ${selected.size} 个分组" else "简播") },
            navigationIcon = {
                if (selecting) IconButton(onClick = { selecting = false; selected = emptySet() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "退出批量管理")
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
                        IconButton(onClick = restoreGroups) { Icon(Icons.Default.Restore, "恢复隐藏分组") }
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
                            FilledTonalButton(onClick = requestPermission) { Text("授权自动扫描") }
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
                                if (selecting) selected = selected.toggle(group.key) else openGroup(group)
                            },
                            onLongClick = { selecting = true; selected = selected + group.key },
                        ),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (checked) Icons.Default.CheckCircle else Icons.Default.Folder, null, Modifier.size(40.dp))
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(group.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${group.videos.size} 个视频 · ${if (group.source == VideoGroup.Source.MANUAL) "手动导入" else "自动扫描"}")
                            }
                        }
                    }
                }
            }
            if (state.isLoading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }

    if (confirmHide) AlertDialog(
        onDismissRequest = { confirmHide = false },
        title = { Text("从简播移除分组？") },
        text = { Text("只隐藏所选分组，不会删除手机中的视频文件。以后可用右上角恢复按钮重新显示。") },
        confirmButton = {
            TextButton(onClick = {
                hideGroups(selected)
                selected = emptySet()
                selecting = false
                confirmHide = false
            }) { Text("仅移除分组") }
        },
        dismissButton = { TextButton(onClick = { confirmHide = false }) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupScreen(group: VideoGroup, onBack: () -> Unit, play: (Int) -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${group.videos.size} 个视频", style = MaterialTheme.typography.labelSmall)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(10.dp)) {
            itemsIndexed(group.videos, key = { _, v -> v.id }) { index, video ->
                Card(onClick = { play(index) }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VideoFile, null, Modifier.size(42.dp))
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(video.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(formatDuration(video.durationMs), style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.PlayCircle, "播放")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(granted: Boolean, request: () -> Unit, importFolder: () -> Unit) {
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

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "时长未知"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%02d:%02d".format(s / 60, s % 60)
}
