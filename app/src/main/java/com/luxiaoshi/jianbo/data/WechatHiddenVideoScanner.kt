package com.luxiaoshi.jianbo.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.ArrayDeque
import java.util.Locale

/**
 * Deep scan for WeChat videos that live in shared storage but are absent from MediaStore.
 *
 * Android 11+ requires the user to explicitly grant all-files access before this scanner runs.
 * The scan is intentionally limited to known WeChat shared-storage roots and never traverses
 * Android/data or another app's private storage.
 */
class WechatHiddenVideoScanner(private val context: Context) {

    suspend fun mergeInto(baseGroups: List<VideoGroup>): List<VideoGroup> = withContext(Dispatchers.IO) {
        if (!hasScanAccess()) return@withContext baseGroups
        if (GROUP_KEY in hiddenGroupKeys()) return@withContext baseGroups

        val roots = wechatRoots()
        if (roots.isEmpty()) return@withContext baseGroups

        val indexedPaths = queryIndexedPaths(roots)
        val existingStorageIdentities = baseGroups.asSequence()
            .flatMap { it.videos.asSequence() }
            .mapNotNull(VideoItem::storageIdentity)
            .mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }

        val videos = scanRoots(roots, indexedPaths, existingStorageIdentities)
            .distinctBy { it.storageIdentity ?: it.id }
            .sortedWith(FILE_NAME_COMPARATOR)
        if (videos.isEmpty()) return@withContext baseGroups

        val existingIds = baseGroups.asSequence()
            .flatMap { it.videos.asSequence() }
            .mapTo(hashSetOf(), VideoItem::id)
        val extras = videos.filterNot { it.id in existingIds }
        if (extras.isEmpty()) return@withContext baseGroups

        (baseGroups + VideoGroup(
            key = GROUP_KEY,
            name = GROUP_NAME,
            videos = extras,
            source = VideoGroup.Source.AUTO,
        )).sortedWith(
            compareBy<VideoGroup> { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.source.name },
        )
    }

    private fun hasScanAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun wechatRoots(): List<File> {
        val sharedRoot = Environment.getExternalStorageDirectory()
        val candidates = listOf(
            File(sharedRoot, "Android/media/com.tencent.mm"),
            File(sharedRoot, "Tencent/MicroMsg"),
            File(sharedRoot, "tencent/MicroMsg"),
        )
        return candidates
            .mapNotNull { candidate ->
                runCatching { candidate.canonicalFile }.getOrNull()
                    ?.takeIf { it.isDirectory && it.canRead() }
            }
            .distinctBy { it.absolutePath }
    }

    private fun scanRoots(
        roots: List<File>,
        indexedPaths: Set<String>,
        existingStorageIdentities: Set<String>,
    ): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val queue = ArrayDeque<File>()
        val visitedDirectories = hashSetOf<String>()
        roots.forEach(queue::add)

        while (queue.isNotEmpty()) {
            val directory = queue.removeFirst()
            val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: continue
            val directoryPath = canonicalDirectory.absolutePath
            if (!visitedDirectories.add(directoryPath)) continue

            val children = runCatching { canonicalDirectory.listFiles().orEmpty() }
                .getOrDefault(emptyArray())
            for (child in children) {
                when {
                    child.isDirectory -> queue.addLast(child)
                    child.isFile && child.canRead() -> {
                        val file = runCatching { child.canonicalFile }.getOrNull() ?: continue
                        if (!looksLikeVideo(file)) continue
                        if (file.absolutePath in indexedPaths) continue

                        val storageIdentity = storageIdentity(file)
                        if (storageIdentity != null && storageIdentity in existingStorageIdentities) continue

                        val metadata = readVideoMetadata(file)
                        result += VideoItem(
                            id = "wechat-file:${file.absolutePath}",
                            uri = Uri.fromFile(file),
                            name = file.name.ifBlank { "未命名视频" },
                            folderKey = GROUP_KEY,
                            folderName = GROUP_NAME,
                            durationMs = metadata.durationMs,
                            width = metadata.width,
                            height = metadata.height,
                            rotationDegrees = metadata.rotationDegrees,
                            dateAddedSeconds = (file.lastModified() / 1_000L).coerceAtLeast(0L),
                            folderLocation = file.parentFile?.absolutePath?.let(::displayAbsoluteLocation),
                            storageIdentity = storageIdentity,
                        )
                    }
                }
            }
        }
        return result
    }

    private fun queryIndexedPaths(roots: List<File>): Set<String> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        @Suppress("DEPRECATION")
        val dataColumn = MediaStore.MediaColumns.DATA
        val rootPaths = roots.map(File::getAbsolutePath)
        val selection = rootPaths.joinToString(separator = " OR ") { "$dataColumn LIKE ?" }
        val selectionArgs = rootPaths.map { "$it/%" }.toTypedArray()
        val result = hashSetOf<String>()

        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(dataColumn),
                selection,
                selectionArgs,
                null,
            )?.use { cursor ->
                val pathColumn = cursor.getColumnIndex(dataColumn)
                while (cursor.moveToNext()) {
                    if (pathColumn < 0 || cursor.isNull(pathColumn)) continue
                    val rawPath = cursor.getString(pathColumn)
                    val normalized = runCatching { File(rawPath).canonicalPath }
                        .getOrDefault(rawPath)
                    result += normalized
                }
            }
        }
        return result
    }

    private fun looksLikeVideo(file: File): Boolean {
        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension in VIDEO_EXTENSIONS) return true
        if (file.length() < UNKNOWN_CONTAINER_SNIFF_MIN_BYTES) return false
        return hasVideoContainerSignature(file)
    }

    private fun hasVideoContainerSignature(file: File): Boolean {
        val header = ByteArray(16)
        val bytesRead = runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrDefault(-1)
        if (bytesRead < 4) return false

        if (bytesRead >= 8 && header.asText(4, 4) == "ftyp") return true
        if (bytesRead >= 12 && header.asText(0, 4) == "RIFF" && header.asText(8, 4) == "AVI ") return true
        if (bytesRead >= 3 && header.asText(0, 3) == "FLV") return true
        if (bytesRead >= 4 &&
            header[0].u() == 0x1A && header[1].u() == 0x45 &&
            header[2].u() == 0xDF && header[3].u() == 0xA3
        ) return true
        return false
    }

    private fun readVideoMetadata(file: File): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
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

    private fun storageIdentity(file: File): String? {
        val sharedRoot = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
            ?: return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val rootPath = sharedRoot.absolutePath.trimEnd('/')
        val path = canonical.absolutePath
        if (path != rootPath && !path.startsWith("$rootPath/")) return null
        val relative = path.removePrefix(rootPath).trimStart('/')
        return "${MediaStore.VOLUME_EXTERNAL_PRIMARY}:$relative".lowercase(Locale.ROOT)
    }

    private fun displayAbsoluteLocation(path: String): String {
        val sharedPrefix = "/storage/emulated/0"
        return if (path == sharedPrefix || path.startsWith("$sharedPrefix/")) {
            "内部存储${path.removePrefix(sharedPrefix)}"
        } else {
            path
        }
    }

    private fun hiddenGroupKeys(): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN_GROUPS, emptySet())
            .orEmpty()
            .toSet()

    private fun ByteArray.asText(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.ISO_8859_1)

    private fun Byte.u(): Int = toInt() and 0xFF

    private data class VideoMetadata(
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val rotationDegrees: Int = 0,
    )

    private companion object {
        const val PREFS_NAME = "jianbo_library"
        const val KEY_HIDDEN_GROUPS = "hidden_group_keys"
        const val GROUP_KEY = "wechat-hidden:shared-storage"
        const val GROUP_NAME = "微信隐藏视频"
        const val UNKNOWN_CONTAINER_SNIFF_MIN_BYTES = 256L * 1024L

        val VIDEO_EXTENSIONS = setOf(
            "3g2", "3gp", "amv", "asf", "avi", "divx", "dv", "f4v", "flv",
            "m2t", "m2ts", "m4v", "mkv", "mov", "mp2", "mp4", "mpe", "mpeg",
            "mpg", "mts", "mxf", "ogm", "ogv", "rm", "rmvb", "ts", "vob",
            "webm", "wmv",
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
