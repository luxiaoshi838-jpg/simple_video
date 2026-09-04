package com.luxiaoshi.jianbo.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class ExternalUriLocation(
    val fileLocation: String?,
    val folderLocation: String?,
    val suggestedFolderUri: Uri?,
    val rawUri: String,
)

/**
 * Best-effort resolver for a video URI handed to Jianbo by another app.
 *
 * The resolver never invents a filesystem path. It tries, in order:
 * 1) file:// path;
 * 2) ExternalStorageProvider document id;
 * 3) MediaStore relative path + volume + display name;
 * 4) legacy/provider _data column when exposed by the provider;
 * 5) the opened file descriptor's /proc/self/fd symlink.
 *
 * If Android/provider policy hides the physical path, callers must show the original content URI
 * rather than pretending that a parent folder is known.
 */
class ExternalUriLocationResolver(private val context: Context) {

    suspend fun resolve(uri: Uri): ExternalUriLocation = withContext(Dispatchers.IO) {
        val rawPath = when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> uri.path
            "content" -> externalStorageDocumentPath(uri)
                ?: queryMediaStorePath(uri)
                ?: queryDataPath(uri)
                ?: fileDescriptorPath(uri)
            else -> null
        }
            ?.let(::normalizePath)
            ?.takeIf(::looksLikeFilesystemPath)

        val folderPath = rawPath
            ?.let { File(it).parent }
            ?.takeIf { it.isNotBlank() }

        val suggested = folderPath
            ?.let(::buildExternalStorageFolderUri)
            ?: fallbackWechatSharedFolderUri(uri)

        ExternalUriLocation(
            fileLocation = rawPath?.let(::displayPath),
            folderLocation = folderPath?.let(::displayPath),
            suggestedFolderUri = suggested,
            rawUri = uri.toString(),
        )
    }

    private fun externalStorageDocumentPath(uri: Uri): String? {
        if (uri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) return null
        val documentId = runCatching {
            when {
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                else -> null
            }
        }.getOrNull() ?: return null

        if (documentId.startsWith("raw:")) {
            return documentId.removePrefix("raw:").takeIf(String::isNotBlank)
        }

        val volume = documentId.substringBefore(':', missingDelimiterValue = "")
        val relative = documentId.substringAfter(':', missingDelimiterValue = "").trimStart('/')
        if (volume.isBlank()) return null
        val root = if (volume.equals("primary", ignoreCase = true)) {
            PRIMARY_STORAGE_ROOT
        } else {
            "/storage/$volume"
        }
        return if (relative.isBlank()) root else "$root/$relative"
    }

    private fun queryMediaStorePath(uri: Uri): String? {
        val projection = buildList {
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            }
        }.toTypedArray()

        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val relativeIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                val volumeIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                } else {
                    -1
                }
                val name = cursor.stringOrNull(nameIndex) ?: return@use null
                val relative = cursor.stringOrNull(relativeIndex)?.trim('/') ?: return@use null
                val volume = cursor.stringOrNull(volumeIndex)
                val root = if (volume.isNullOrBlank() || volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                    PRIMARY_STORAGE_ROOT
                } else {
                    "/storage/$volume"
                }
                if (relative.isBlank()) "$root/$name" else "$root/$relative/$name"
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun queryDataPath(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getString(index)
            } else {
                null
            }
        }
    }.getOrNull()

    private fun fileDescriptorPath(uri: Uri): String? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            Os.readlink("/proc/self/fd/${descriptor.fd}")
                .removeSuffix(" (deleted)")
                .takeIf(String::isNotBlank)
        }
    }.getOrNull()

    private fun normalizePath(path: String): String {
        val cleaned = path.trim().removeSuffix("/")
        val sdcardNormalized = when {
            cleaned == "/sdcard" -> PRIMARY_STORAGE_ROOT
            cleaned.startsWith("/sdcard/") -> PRIMARY_STORAGE_ROOT + cleaned.removePrefix("/sdcard")
            else -> cleaned
        }
        return runCatching { File(sdcardNormalized).canonicalPath }.getOrDefault(sdcardNormalized)
    }

    private fun looksLikeFilesystemPath(path: String): Boolean =
        path.startsWith("/") &&
            !path.startsWith("/proc/") &&
            !path.startsWith("/dev/") &&
            !path.startsWith("/sys/")

    private fun displayPath(path: String): String = when {
        path == PRIMARY_STORAGE_ROOT -> "内部存储"
        path.startsWith("$PRIMARY_STORAGE_ROOT/") -> "内部存储${path.removePrefix(PRIMARY_STORAGE_ROOT)}"
        else -> path
    }

    private fun buildExternalStorageFolderUri(folderPath: String): Uri? {
        val normalized = normalizePath(folderPath)
        val documentId = when {
            normalized == PRIMARY_STORAGE_ROOT -> return null
            normalized.startsWith("$PRIMARY_STORAGE_ROOT/") -> {
                "primary:${normalized.removePrefix("$PRIMARY_STORAGE_ROOT/")}"
            }
            normalized.startsWith("/storage/") -> {
                val rest = normalized.removePrefix("/storage/")
                val volume = rest.substringBefore('/', missingDelimiterValue = "")
                val relative = rest.substringAfter('/', missingDelimiterValue = "")
                if (volume.isBlank() || relative.isBlank()) return null
                "$volume:$relative"
            }
            else -> return null
        }
        return runCatching {
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY, documentId)
        }.getOrNull()
    }

    private fun fallbackWechatSharedFolderUri(sourceUri: Uri): Uri? {
        val authority = sourceUri.authority.orEmpty().lowercase(Locale.ROOT)
        if (authority.none { false }) {
            // Keep this branch intentionally empty; actual package matching is below.
        }
        val looksLikeWechat = authority.contains("tencent.mm") ||
            authority.contains("wechat") ||
            authority.contains("micromsg")
        if (!looksLikeWechat) return null

        val folder = File(PRIMARY_STORAGE_ROOT, "Android/media/com.tencent.mm")
        if (!folder.isDirectory) return null
        return buildExternalStorageFolderUri(folder.absolutePath)
    }

    private fun android.database.Cursor.stringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    private companion object {
        const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
        const val PRIMARY_STORAGE_ROOT = "/storage/emulated/0"
    }
}
