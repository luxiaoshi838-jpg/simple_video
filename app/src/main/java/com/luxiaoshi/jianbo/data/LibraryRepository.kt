package com.luxiaoshi.jianbo.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

class LibraryRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun loadLibrary(hasMediaPermission: Boolean): List<VideoGroup> = withContext(Dispatchers.IO) {
        val videos = buildList {
            if (hasMediaPermission) addAll(queryMediaStore())
            addAll(queryManualTrees())
        }
        val hidden = hiddenGroupKeys()
        videos
            .groupBy(VideoItem::folderKey)
            .mapNotNull { (key, items) ->
                if (key in hidden || items.isEmpty()) return@mapNotNull null
                val source = if (key.startsWith("saf:")) VideoGroup.Source.MANUAL else VideoGroup.Source.AUTO
                VideoGroup(
                    key = key,
                    name = items.first().folderName,
                    videos = items.sortedWith(FILE_NAME_COMPARATOR),
                    source = source,
                )
            }
            .sortedWith(
                compareBy<VideoGroup> { it.name.lowercase() }
                    .thenBy { it.source.name },
            )
    }

    suspend fun resolveExternalOpen(intent: Intent): ExternalOpenRequest? = withContext(Dispatchers.IO) {
        val uri = externalUri(intent) ?: return@withContext null
        if (uri.scheme != "content" && uri.scheme != "file") return@withContext null

        if (uri.scheme == "content" &&
            intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        ) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }

        val name = queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "外部视频"
        val mime = runCatching { context.contentResolver.getType(uri) }
            .getOrNull()
            .orEmpty()
            .lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        if (!mime.startsWith("video/") &&
            mime !in EXTRA_VIDEO_MIME_TYPES &&
            extension !in VIDEO_EXTENSIONS
        ) {
            return@withContext null
        }

        val metadata = readVideoMetadata(uri)
        val documentId = externalStorageDocumentId(uri)
        val parentDocumentId = documentId?.let(::parentDocumentId)
        val folderLocation = parentDocumentId?.let(::displayDocumentLocation)
        val suggestedFolderUri = if (parentDocumentId != null && uri.authority != null) {
            runCatching {
                DocumentsContract.buildDocumentUri(requireNotNull(uri.authority), parentDocumentId)
            }.getOrNull()
        } else {
            null
        }

        ExternalOpenRequest(
            video = VideoItem(
                id = "external:$uri",
                uri = uri,
                name = name,
                folderKey = "external:${uri.authority ?: uri.scheme}",
                folderName = folderLocation?.substringAfterLast('/') ?: "外部打开",
                durationMs = metadata.durationMs,
                width = metadata.width,
                height = metadata.height,
                rotationDegrees = metadata.rotationDegrees,
                folderLocation = folderLocation,
                storageIdentity = documentId?.let(::storageIdentityFromDocumentId),
            ),
            suggestedFolderUri = suggestedFolderUri,
        )
    }

    fun addManualTree(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val trees = manualTreeUris().toMutableSet()
        trees += uri.toString()
        preferences.edit().putStringSet(KEY_MANUAL_TREES, trees).apply()
    }

    fun hideGroups(keys: Set<String>) {
        if (keys.isEmpty()) return
        val hidden = hiddenGroupKeys().toMutableSet()
        hidden += keys
        preferences.edit().putStringSet(KEY_HIDDEN_GROUPS, hidden).apply()
    }

    fun restoreHiddenGroups() {
        preferences.edit().remove(KEY_HIDDEN_GROUPS).apply()
    }

    fun hiddenGroupCount(): Int = hiddenGroupKeys().size

    private fun hiddenGroupKeys(): Set<String> =
        preferences.getStringSet(KEY_HIDDEN_GROUPS, emptySet()).orEmpty().toSet()

    private fun manualTreeUris(): Set<String> =
        preferences.getStringSet(KEY_MANUAL_TREES, emptySet()).orEmpty().toSet()

    private fun queryMediaStore(): List<VideoItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.BUCKET_ID)
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
            add(MediaStore.Video.Media.ORIENTATION)
            add(MediaStore.Video.Media.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Video.Media.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Video.Media.DATA)
            }
        }.toTypedArray()

        val result = mutableListOf<VideoItem>()
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val bucketIdColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val widthColumn = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            val orientationColumn = cursor.getColumnIndex(MediaStore.Video.Media.ORIENTATION)
            val dateColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
            } else {
                -1
            }
            val volumeColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
            } else {
                -1
            }
            val dataColumn = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                val name = cursor.stringOrNull(nameColumn) ?: "未命名视频"
                val relativePath = cursor.stringOrNull(relativePathColumn)
                val volumeName = cursor.stringOrNull(volumeColumn)
                val rawPath = cursor.stringOrNull(dataColumn)
                val bucketId = cursor.stringOrNull(bucketIdColumn)
                    ?: relativePath
                    ?: rawPath?.substringBeforeLast('/')
                    ?: "unknown"
                val folderName = cursor.stringOrNull(bucketNameColumn)
                    ?: relativePath?.trimEnd('/')?.substringAfterLast('/')
                    ?: rawPath?.substringBeforeLast('/')?.substringAfterLast('/')
                    ?: "未命名文件夹"
                val folderLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    displayMediaStoreLocation(relativePath, volumeName)
                } else {
                    rawPath?.substringBeforeLast('/')?.let(::displayAbsoluteLocation)
                }
                val storageIdentity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mediaStoreStorageIdentity(volumeName, relativePath, name)
                } else {
                    rawPath?.let(::canonicalLowercasePath)
                }
                result += VideoItem(
                    id = "media:$id",
                    uri = uri,
                    name = name,
                    folderKey = "media:$bucketId",
                    folderName = folderName,
                    durationMs = cursor.longOrZero(durationColumn),
                    width = cursor.intOrZero(widthColumn),
                    height = cursor.intOrZero(heightColumn),
                    rotationDegrees = normalizeRotation(cursor.intOrZero(orientationColumn)),
                    dateAddedSeconds = cursor.longOrZero(dateColumn),
                    folderLocation = folderLocation,
                    storageIdentity = storageIdentity,
                )
            }
        }
        return result
    }

    private fun queryManualTrees(): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        for (treeString in manualTreeUris()) {
            val treeUri = runCatching { Uri.parse(treeString) }.getOrNull() ?: continue
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: continue
            val queue = ArrayDeque<DocumentFile>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val directory = queue.removeFirst()
                val groupName = directory.name ?: root.name ?: "手动导入"
                val groupKey = "saf:${directory.uri}"
                val groupLocation = describeDocumentLocation(directory.uri)
                    ?: describeDocumentLocation(root.uri)
                    ?: "已授权文件夹"
                val children = runCatching { directory.listFiles().toList() }.getOrDefault(emptyList())
                for (child in children) {
                    when {
                        child.isDirectory -> queue.add(child)
                        child.isFile && isVideoFile(child) -> {
                            val metadata = readVideoMetadata(child.uri)
                            result += VideoItem(
                                id = "saf:${child.uri}",
                                uri = child.uri,
                                name = child.name ?: "未命名视频",
                                folderKey = groupKey,
                                folderName = groupName,
                                durationMs = metadata.durationMs,
                                width = metadata.width,
                                height = metadata.height,
                                rotationDegrees = metadata.rotationDegrees,
                                dateAddedSeconds = child.lastModified() / 1_000L,
                                folderLocation = groupLocation,
                                storageIdentity = externalStorageDocumentId(child.uri)
                                    ?.let(::storageIdentityFromDocumentId),
                            )
                        }
                    }
                }
            }
        }
        return result
    }

    private fun externalUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        else -> null
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getString(column) else null
            }
        }.getOrNull()
    }

    private fun readVideoMetadata(uri: Uri): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            VideoMetadata(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                    ?.coerceAtLeast(0)
                    ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                    ?.coerceAtLeast(0)
                    ?: 0,
                rotationDegrees = normalizeRotation(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull()
                        ?: 0,
                ),
            )
        } catch (_: Throwable) {
            VideoMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isVideoFile(file: DocumentFile): Boolean {
        val mimeType = file.type.orEmpty().lowercase(Locale.ROOT)
        if (mimeType.startsWith("video/")) return true
        val extension = file.name
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return extension in VIDEO_EXTENSIONS
    }

    private fun describeDocumentLocation(uri: Uri): String? =
        externalStorageDocumentId(uri)?.let(::displayDocumentLocation)

    private fun externalStorageDocumentId(uri: Uri): String? {
        if (uri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) return null
        return runCatching {
            when {
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                else -> null
            }
        }.getOrNull()
    }

    private fun parentDocumentId(documentId: String): String? {
        val volume = documentId.substringBefore(':', missingDelimiterValue = "")
        val path = documentId.substringAfter(':', missingDelimiterValue = "")
        if (volume.isBlank()) return null
        val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
        return "$volume:$parentPath"
    }

    private fun displayDocumentLocation(documentId: String): String {
        val volume = documentId.substringBefore(':', missingDelimiterValue = documentId)
        val path = documentId.substringAfter(':', missingDelimiterValue = "").trim('/')
        val rootName = if (volume.equals("primary", ignoreCase = true)) "内部存储" else volume
        return if (path.isBlank()) rootName else "$rootName/$path"
    }

    private fun storageIdentityFromDocumentId(documentId: String): String {
        val volume = documentId.substringBefore(':', missingDelimiterValue = "primary")
        val path = documentId.substringAfter(':', missingDelimiterValue = "").trim('/')
        val mediaVolume = if (volume.equals("primary", ignoreCase = true)) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            volume
        }
        return "$mediaVolume:$path".lowercase(Locale.ROOT)
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
        const val KEY_MANUAL_TREES = "manual_tree_uris"
        const val KEY_HIDDEN_GROUPS = "hidden_group_keys"
        const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"

        val EXTRA_VIDEO_MIME_TYPES = setOf(
            "application/octet-stream",
            "application/mp4",
            "application/x-matroska",
        )

        val VIDEO_EXTENSIONS = setOf(
            "3g2", "3gp", "amv", "asf", "avi", "divx", "dv", "f4v", "flv",
            "m2t", "m2ts", "m4v", "mkv", "mov", "mp2", "mp4", "mpe", "mpeg",
            "mpg", "mts", "mxf", "ogm", "ogv", "rm", "rmvb", "ts", "vob",
            "webm", "wmv",
        )

        val FILE_NAME_COMPARATOR = Comparator<VideoItem> { left, right ->
            compareNaturalFileNames(left.name, right.name)
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

        fun compareNaturalFileNames(left: String, right: String): Int {
            val a = left.lowercase(Locale.getDefault())
            val b = right.lowercase(Locale.getDefault())
            var i = 0
            var j = 0

            while (i < a.length && j < b.length) {
                val aDigit = a[i].isDigit()
                val bDigit = b[j].isDigit()

                if (aDigit && bDigit) {
                    val aStart = i
                    val bStart = j
                    while (i < a.length && a[i].isDigit()) i++
                    while (j < b.length && b[j].isDigit()) j++

                    val aRaw = a.substring(aStart, i)
                    val bRaw = b.substring(bStart, j)
                    val aNumber = aRaw.trimStart('0').ifEmpty { "0" }
                    val bNumber = bRaw.trimStart('0').ifEmpty { "0" }

                    if (aNumber.length != bNumber.length) {
                        return aNumber.length.compareTo(bNumber.length)
                    }
                    val numberCompare = aNumber.compareTo(bNumber)
                    if (numberCompare != 0) return numberCompare
                    if (aRaw.length != bRaw.length) return aRaw.length.compareTo(bRaw.length)
                } else {
                    val charCompare = a[i].compareTo(b[j])
                    if (charCompare != 0) return charCompare
                    i++
                    j++
                }
            }

            return when {
                i < a.length -> 1
                j < b.length -> -1
                else -> left.compareTo(right)
            }
        }
    }
}
