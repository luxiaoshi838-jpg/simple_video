package com.luxiaoshi.jianbo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.luxiaoshi.jianbo.data.ExternalOpenRequest
import com.luxiaoshi.jianbo.data.ExternalUriLocation
import com.luxiaoshi.jianbo.data.ExternalUriLocationResolver
import com.luxiaoshi.jianbo.data.LibraryRepository
import com.luxiaoshi.jianbo.player.PlayerScreen
import com.luxiaoshi.jianbo.ui.theme.JianboTheme

class ExternalOpenActivity : ComponentActivity() {
    private val repository by lazy { LibraryRepository(applicationContext) }
    private val locationResolver by lazy { ExternalUriLocationResolver(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JianboTheme {
                ExternalOpenRoute(
                    incomingIntent = intent,
                    repository = repository,
                    locationResolver = locationResolver,
                    finishActivity = ::finish,
                )
            }
        }
    }
}

private sealed interface ExternalOpenState {
    data object Loading : ExternalOpenState
    data class Ready(
        val request: ExternalOpenRequest,
        val location: ExternalUriLocation,
    ) : ExternalOpenState
    data object Unsupported : ExternalOpenState
}

@Composable
private fun ExternalOpenRoute(
    incomingIntent: Intent,
    repository: LibraryRepository,
    locationResolver: ExternalUriLocationResolver,
    finishActivity: () -> Unit,
) {
    val state by produceState<ExternalOpenState>(
        initialValue = ExternalOpenState.Loading,
        key1 = incomingIntent.action,
        key2 = incomingIntent.dataString,
    ) {
        val request = repository.resolveExternalOpen(incomingIntent)
        value = if (request == null) {
            ExternalOpenState.Unsupported
        } else {
            ExternalOpenState.Ready(
                request = request,
                location = locationResolver.resolve(request.video.uri),
            )
        }
    }

    when (val current = state) {
        ExternalOpenState.Loading -> LoadingExternalVideo()
        ExternalOpenState.Unsupported -> UnsupportedExternalVideo(finishActivity)
        is ExternalOpenState.Ready -> ExternalVideoPlayer(
            request = current.request,
            location = current.location,
            repository = repository,
            finishActivity = finishActivity,
        )
    }
}

@Composable
private fun ExternalVideoPlayer(
    request: ExternalOpenRequest,
    location: ExternalUriLocation,
    repository: LibraryRepository,
    finishActivity: () -> Unit,
) {
    var playerVisible by remember(request.video.id) { mutableStateOf(true) }
    var permissionDialogVisible by remember(request.video.id) { mutableStateOf(false) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) repository.addManualTree(uri)
        finishActivity()
    }

    if (playerVisible) {
        PlayerScreen(
            videos = listOf(request.video),
            startIndex = 0,
            onExit = {
                playerVisible = false
                permissionDialogVisible = true
            },
        )
        return
    }

    if (permissionDialogVisible) {
        val fileLocation = location.fileLocation
        val folderLocation = location.folderLocation ?: request.video.folderLocation
        val suggestedFolderUri = location.suggestedFolderUri ?: request.suggestedFolderUri
        val privateFolder = folderLocation?.startsWith("/data/") == true ||
            folderLocation?.startsWith("/mnt/expand/") == true

        AlertDialog(
            onDismissRequest = finishActivity,
            title = { Text("加入这个视频所在文件夹？") },
            text = {
                SelectionContainer {
                    Text(
                        buildString {
                            append("文件位置：")
                            append(fileLocation ?: "真实路径不可读取")
                            append("\n\n所在文件夹：")
                            append(folderLocation ?: "真实路径不可读取")

                            if (fileLocation == null || folderLocation == null) {
                                append("\n\n来源 URI：")
                                append(location.rawUri)
                            }

                            append("\n\n")
                            when {
                                privateFolder -> append(
                                    "这个位置属于其他应用的私有存储。Android 允许简播临时读取当前视频，但不允许简播取得整个父文件夹权限。",
                                )
                                suggestedFolderUri != null -> append(
                                    "系统仍要求你确认一次文件夹访问权限。选择器会尽量定位到上面显示的父文件夹；确认后简播会长期记住该文件夹。",
                                )
                                else -> append(
                                    "系统没有提供可直接定位父目录的入口。若文件夹选择器停在“内部存储”根目录，请进入上面显示的具体子文件夹后再点“使用此文件夹”；内部存储根目录本身不能授权。",
                                )
                            }
                        },
                    )
                }
            },
            confirmButton = {
                if (!privateFolder) {
                    Button(onClick = { folderLauncher.launch(suggestedFolderUri) }) {
                        Text(if (suggestedFolderUri != null) "授权所在文件夹" else "手动选择具体文件夹")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = finishActivity) {
                    Text(if (privateFolder) "完成" else "仅播放这一次")
                }
            },
        )
    }
}

@Composable
private fun LoadingExternalVideo() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            "正在读取视频并解析真实位置…",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun UnsupportedExternalVideo(finishActivity: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("简播无法读取这个文件。", style = MaterialTheme.typography.titleMedium)
        Text(
            "如果微信只提供了临时文件而没有视频类型信息，请先在微信中完成本地保存后再试。",
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        Button(onClick = finishActivity) { Text("返回") }
    }
}
