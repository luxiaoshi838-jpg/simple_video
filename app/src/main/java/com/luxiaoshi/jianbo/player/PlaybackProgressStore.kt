package com.luxiaoshi.jianbo.player

import android.content.Context
import com.luxiaoshi.jianbo.data.VideoItem

internal class PlaybackProgressStore(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadResumePosition(video: VideoItem): Long {
        val stored = preferences.getLong(key(video), 0L).coerceAtLeast(0L)
        val normalized = normalizeResumePosition(stored, video.durationMs)
        if (stored > 0L && normalized == 0L && video.durationMs > 0L) clear(video)
        return normalized
    }

    fun save(video: VideoItem, positionMs: Long) {
        val safePosition = positionMs.coerceAtLeast(0L)
        val editor = preferences.edit()
        if (safePosition > 0L) editor.putLong(key(video), safePosition)
        else editor.remove(key(video))
        editor.apply()
    }

    fun clear(video: VideoItem) {
        preferences.edit().remove(key(video)).apply()
    }

    private fun key(video: VideoItem): String {
        val identity = video.storageIdentity
            ?.takeIf { it.isNotBlank() }
            ?: video.uri.toString().takeIf { it.isNotBlank() }
            ?: video.id
        return "video:$identity"
    }

    private companion object {
        const val PREFERENCES_NAME = "jianbo_playback_progress"
    }
}
