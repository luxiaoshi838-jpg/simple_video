package com.luxiaoshi.jianbo.data

import android.net.Uri

data class VideoItem(
    val id: String,
    val uri: Uri,
    val name: String,
    val folderKey: String,
    val folderName: String,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val dateAddedSeconds: Long = 0L,
)

data class VideoGroup(
    val key: String,
    val name: String,
    val videos: List<VideoItem>,
    val source: Source,
) {
    enum class Source { AUTO, MANUAL }
    val totalDurationMs: Long get() = videos.sumOf(VideoItem::durationMs)
}

data class LibraryUiState(
    val isLoading: Boolean = false,
    val permissionGranted: Boolean = false,
    val groups: List<VideoGroup> = emptyList(),
    val hiddenGroupCount: Int = 0,
    val errorMessage: String? = null,
)
