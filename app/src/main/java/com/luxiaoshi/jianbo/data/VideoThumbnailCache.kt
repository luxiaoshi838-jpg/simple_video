package com.luxiaoshi.jianbo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object VideoThumbnailCache {
    private const val MEMORY_CACHE_KB = 24 * 1024
    private const val THUMBNAIL_WIDTH = 448
    private const val THUMBNAIL_HEIGHT = 256

    private val memoryCache = object : LruCache<String, Bitmap>(MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun peek(video: VideoItem): Bitmap? = memoryCache.get(cacheKey(video))

    suspend fun load(context: Context, video: VideoItem): Bitmap? = withContext(Dispatchers.IO) {
        val key = cacheKey(video)
        memoryCache.get(key)?.let { return@withContext it }

        val mutex = locks.getOrPut(key) { Mutex() }
        try {
            mutex.withLock {
                memoryCache.get(key)?.let { return@withLock it }

                val cacheFile = thumbnailFile(context, key)
                decodeCachedBitmap(cacheFile)?.let {
                    memoryCache.put(key, it)
                    return@withLock it
                }

                val generated = createThumbnail(context, video) ?: return@withLock null
                memoryCache.put(key, generated)
                writeAtomically(cacheFile, generated)
                generated
            }
        } finally {
            locks.remove(key, mutex)
        }
    }

    private fun createThumbnail(context: Context, video: VideoItem): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, video.uri)
            val frameTimeUs = when {
                video.durationMs > 4_000L -> 2_000_000L
                video.durationMs > 1_000L -> video.durationMs * 500L
                else -> 0L
            }
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    frameTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMBNAIL_WIDTH,
                    THUMBNAIL_HEIGHT,
                )
            } else {
                retriever.getFrameAtTime(
                    frameTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )?.let { source ->
                    val scaled = Bitmap.createScaledBitmap(
                        source,
                        THUMBNAIL_WIDTH,
                        THUMBNAIL_HEIGHT,
                        true,
                    )
                    if (scaled !== source) source.recycle()
                    scaled
                }
            }
            frame?.takeUnless { it.isRecycled }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun thumbnailFile(context: Context, key: String): File {
        val directory = File(context.cacheDir, "video_thumbnails_v1")
        if (!directory.exists()) directory.mkdirs()
        return File(directory, "$key.jpg")
    }

    private fun decodeCachedBitmap(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .getOrNull()
            ?.takeUnless { it.isRecycled }
            ?: run {
                file.delete()
                null
            }
    }

    private fun writeAtomically(target: File, bitmap: Bitmap) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output))
                output.fd.sync()
            }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target))
        }.onFailure {
            temporary.delete()
        }
    }

    private fun cacheKey(video: VideoItem): String {
        val source = buildString {
            append(video.id)
            append('|')
            append(video.uri)
            append('|')
            append(video.name)
            append('|')
            append(video.dateAddedSeconds)
            append('|')
            append(video.durationMs)
            append('|')
            append(video.width)
            append('x')
            append(video.height)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
