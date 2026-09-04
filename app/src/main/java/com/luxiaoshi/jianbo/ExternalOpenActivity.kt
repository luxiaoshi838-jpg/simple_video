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
import com.luxiaoshi.jianbo.data.LibraryRepository
import com.luxiaoshi.jianbo.player.PlayerScreen
import com.luxiaoshi.jianbo.ui.theme.JianboTheme

class ExternalOpenActivity : ComponentActivity() {
    private val repository by lazy { LibraryRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JianboTheme {
                ExternalOpenRoute(
                    incomingIntent = intent,
                    repository = repository,
                    finishActivity = ::finish,
                )
            }
        }
    }
}

private sealed interface ExternalOpenState {
    data object Loading : ExternalOpenState
    data class Ready(val request: ExternalOpenRequest) : ExternalOpenState
    data object Unsupported : ExternalOpenState
}

@Composable
private fun ExternalOpenRoute(
    incomingIntent: Intent,
    repository: LibraryRepository,
    finishActivity: () -> Unit,
) {
    val state by produceState<ExternalOpenState>(
        initialValue = ExternalOpenState.Loading,
        key1 = incomingIntent.action,
        key2 = incomingIntent.dataString,
    ) {
        val request = repository.resolveExternalOpen(incomingIntent)
        value = if (request == null) ExternalOpenState.Unsupported else ExternalOpenState.Ready(request)
    }

    when (val current = state) {
        ExternalOpenState.Loading -> LoadingExternalVideo()
        ExternalOpenState.Unsupported -> UnsupportedExternalVideo(finishActivity)
        is ExternalOpenState.Ready -> ExternalVideoPlayer(
            request = current.request,
            repository = repository,
            finishActivity = finishActivity,
        )
    }
}

@Composable
private fun ExternalVideoPlayer(
    request: ExternalOpenRequest,
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
        val knownLocation = request.video.folderLocation
        AlertDialog(
            onDismissRequest = finishActivity,
            title = { Text("加入这个视频所在文件夹？") },
            text = {
                Text(
                    if (knownLocation != null) {
                        "当前视频位置：$knownLocation\n\n系统要求你确认一次文件夹访问权限。确认后，简播会长期记住该文件夹，并自动显示同目录中的视频。"
                    } else {
                        "微信允许简播临时打开了这个视频，但没有把真实父文件夹路径交给简播。请在系统文件夹选择器中确认它所在的文件夹；确认后简播会长期记住该文件夹。"
                    },
                )
            },
            confirmButton = {
                Button(onClick = { folderLauncher.launch(request.suggestedFolderUri) }) {
                    Text("授权所在文件夹")
                }
            },
            dismissButton = {
                TextButton(onClick = finishActivity) { Text("仅播放这一次") }
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
            "正在读取视频…",
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
