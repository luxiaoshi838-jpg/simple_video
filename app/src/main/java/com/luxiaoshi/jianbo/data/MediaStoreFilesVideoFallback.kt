package com.luxiaoshi.jianbo.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Secondary MediaStore scan used when a local video exists in the shared media database
 * but was not returned by MediaStore.Video. This is especially useful for videos saved
 * by chat apps whose media row can lag behind or be classified only in MediaStore.Files.
 *
 * It does not request MANAGE_EXTERNAL_STORAGE and does not bypass Android scoped-storage
 * boundaries. App-private files that Android does not expose through MediaStore still need
 * the existing SAF manual-folder import path.
 */
class MediaStoreFilesVideoFallback(private val context: Context) {

    suspend fun mergeInto(baseGroups: List<VideoGroup>): List<VideoGroup> = withContext(Dispatchers.IO) {
        val existingIds = baseGroups.asSequence()
            .flatMap { it.videos.asSequence() }
            .mapTo(linkedSetOf(), VideoItem::id)
        val extras = queryVideoFiles(existingIds)
        if (extras.isEmpty()) return@withContext baseGroups

        val hidden = hiddenGroupKeys()
        val mutableGroups = baseGroups.map { group -> group.copy(videos = group.videos.toMutableList()) }.toMutableList()
        val autoGroupIndexByName = mutableGroups
            .withIndex()
            .filter { it.value.source == VideoGroup.Source.AUTO }
            .associate { it.value.name.trim().lowercase(Locale.ROOT) to it.index }
            .toMutableMap()

        val unmatched = mutableListOf<VideoItem>()
        for (video in extras) {
            val index = autoGroupIndexByName[video.folderName.trim().lowercase(Locale.ROOT)]
            if (index == null) {
                unmatched += video
                continue
            }
            val group = mutableGroups[index]
            if (group.videos.none { it.id == video.id }) {
                mutableGroups[index] = group.copy(videos = (group.videos + video).sortedWith(FILE_NAME_COMPARATOR))
            }
        }

        unmatched
            .groupBy(VideoItem::folderKey)
            .forEach { (key, videos) ->
                if (key in hidden || videos.isEmpty()) return@forEach
                val name = videos.first().folderName
                mutableGroups += VideoGroup(
                    key = key,
                    name = name,
                    videos = videos.distinctBy(VideoItem::id).sortedWith(FILE_NAME_COMPARATOR),
                    source = VideoGroup.Source.AUTO,
                )
                autoGroupIndexByName.putIfAbsent(name.trim().lowercase(Locale.ROOT), mutableGroups.lastIndex)
            }

        mutableGroups.sortedWith(
            compareBy<VideoGroup> { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.source.name },
        )
    }

    private fun queryVideoFiles(existingIds: Set<String>): List<VideoItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.MediaColumns.DATA)
            }
        }.toTypedArray()

        val selectionParts = mutableListOf(
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?",
            "${MediaStore.MediaColumns.MIME_TYPE} LIKE ?",
        )
        val selectionArgs = mutableListOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            "video/%",
        )
        COMMON_VIDEO_EXTENSIONS.forEach { extension ->
            selectionParts += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            selectionArgs += "%.$extension"
        }

        val result = mutableListOf<VideoItem>()
        context.contentResolver.query(
            collection,
            projection,
            selectionParts.joinToString(prefix = "(", postfix = ")", separator = " OR "),
            selectionArgs.toTypedArray(),
            "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val mediaTypeColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val dateColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            }
            val volumeColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val stableId = "media:$id"
                if (stableId in existingIds) continue

                val name = cursor.stringOrNull(nameColumn) ?: "未命名视频"
                val mime = cursor.stringOrNull(mimeColumn).orEmpty().lowercase(Locale.ROOT)
                val mediaType = cursor.intOrZero(mediaTypeColumn)
                if (!looksLikeVideo(name, mime, mediaType)) continue

                val rawPath = cursor.stringOrNull(pathColumn)
                val volumeName = cursor.stringOrNull(volumeColumn)
                val directoryPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    rawPath?.trim('/')
                } else {
                    rawPath?.substringBeforeLast('/', missingDelimiterValue = "")?.trim('/')
                }
                val folderName = directoryPath
                    ?.substringAfterLast('/')
                    ?.takeIf(String::isNotBlank)
                    ?: "未命名文件夹"
                val folderIdentity = directoryPath
                    ?.takeIf(String::isNotBlank)
                    ?.lowercase(Locale.ROOT)
                    ?: folderName.lowercase(Locale.ROOT)
                val uri = ContentUris.withAppendedId(collection, id)
                val metadata = readVideoMetadata(uri)
                val folderLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    displayMediaStoreLocation(directoryPath, volumeName)
                } else {
                    directoryPath?.let(::displayAbsoluteLocation)
                }
                val storageIdentity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mediaStoreStorageIdentity(volumeName, directoryPath, name)
                } else {
                    rawPath?.let(::canonicalLowercasePath)
                }

                result += VideoItem(
                    id = stableId,
                    uri = uri,
                    name = name,
                    folderKey = "media-file:$folderIdentity",
                    folderName = folderName,
                    durationMs = metadata.durationMs,
                    width = metadata.width,
                    height = metadata.height,
                    rotationDegrees = metadata.rotationDegrees,
                    dateAddedSeconds = cursor.longOrZero(dateColumn),
                    folderLocation = folderLocation,
                    storageIdentity = storageIdentity,
                )
            }
        }
        return result
    }

    private fun looksLikeVideo(name: String, mime: String, mediaType: Int): Boolean {
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) return true
        if (mime.startsWith("video/")) return true
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        return extension in COMMON_VIDEO_EXTENSIONS
    }

    private fun readVideoMetadata(uri: Uri): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            VideoMetadata(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                rotationDegrees = normalizeRotation(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0,
                ),
            )
        } catch (_: Throwable) {
            VideoMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun displayMediaStoreLocation(relativePath: String?, volumeName: String?): String? {
        val path = relativePath?.trim('/')?.takeIf(String::isNotBlank) ?: return null
        val rootName = if (volumeName.isNullOrBlank() || volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            "内部存储"
        } else {
            volumeName
        }
        return "$rootName/$path"
    }

    private fun mediaStoreStorageIdentity(volumeName: String?, relativePath: String?, name: String): String? {
        val path = relativePath?.trim('/') ?: return null
        val volume = volumeName?.takeIf(String::isNotBlank) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        return "$volume:$path/$name".lowercase(Locale.ROOT)
    }

    private fun displayAbsoluteLocation(path: String): String {
        val sharedPrefix = "/storage/emulated/0"
        return if (path == sharedPrefix || path.startsWith("$sharedPrefix/")) {
            "内部存储${path.removePrefix(sharedPrefix)}"
        } else {
            path
        }
    }

    private fun canonicalLowercasePath(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path).lowercase(Locale.ROOT)

    private fun hiddenGroupKeys(): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN_GROUPS, emptySet())
            .orEmpty()
            .toSet()

    private fun android.database.Cursor.stringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    private fun android.database.Cursor.longOrZero(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    private fun android.database.Cursor.intOrZero(index: Int): Int =
        if (index >= 0 && !isNull(index)) getInt(index) else 0

    private data class VideoMetadata(
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val rotationDegrees: Int = 0,
    )

    private companion object {
        const val PREFS_NAME = "jianbo_library"
        const val KEY_HIDDEN_GROUPS = "hidden_group_keys"

        val COMMON_VIDEO_EXTENSIONS = setOf(
            "3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg",
            "mpg", "mts", "rm", "rmvb", "ts", "vob", "webm", "wmv",
        )

        val FILE_NAME_COMPARATOR = Comparator<VideoItem> { left, right ->
            left.name.lowercase(Locale.getDefault()).compareTo(right.name.lowercase(Locale.getDefault()))
        }

        fun normalizeRotation(value: Int): Int {
            val normalized = ((value % 360) + 360) % 360
            return when (normalized) {
                in 45..134 -> 90
                in 135..224 -> 180
                in 225..314 -> 270
                else -> 0
            }
        }
    }
}
