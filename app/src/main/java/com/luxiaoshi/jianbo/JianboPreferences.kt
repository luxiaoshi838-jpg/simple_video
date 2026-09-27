package com.luxiaoshi.jianbo

import android.content.Context
import com.luxiaoshi.jianbo.data.VideoGroup
import com.luxiaoshi.jianbo.player.PlaybackOrientationMode

class JianboPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun playbackOrientationMode(): PlaybackOrientationMode {
        val stored = prefs.getString(KEY_PLAYBACK_ORIENTATION, null)
        return runCatching { PlaybackOrientationMode.valueOf(stored.orEmpty()) }
            .getOrDefault(PlaybackOrientationMode.ADAPTIVE)
    }

    fun setPlaybackOrientationMode(mode: PlaybackOrientationMode) {
        prefs.edit().putString(KEY_PLAYBACK_ORIENTATION, mode.name).apply()
    }

    fun recentFoldersFirst(): Boolean =
        prefs.getBoolean(KEY_RECENT_FOLDERS_FIRST, false)

    fun setRecentFoldersFirst(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RECENT_FOLDERS_FIRST, enabled).apply()
    }

    fun markGroupPlayed(groupKey: String, playedAtMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(lastPlayedKey(groupKey), playedAtMs.coerceAtLeast(0L)).apply()
    }

    fun groupLastPlayedAt(groupKey: String): Long =
        prefs.getLong(lastPlayedKey(groupKey), 0L).coerceAtLeast(0L)

    private fun lastPlayedKey(groupKey: String): String = "$KEY_LAST_PLAYED_PREFIX$groupKey"

    private companion object {
        const val PREFS_NAME = "jianbo_preferences"
        const val KEY_PLAYBACK_ORIENTATION = "playback_orientation"
        const val KEY_RECENT_FOLDERS_FIRST = "recent_folders_first"
        const val KEY_LAST_PLAYED_PREFIX = "group_last_played:"
    }
}

internal fun sortGroupsForDisplay(
    groups: List<VideoGroup>,
    recentFirst: Boolean,
    lastPlayedAt: (String) -> Long,
): List<VideoGroup> {
    if (!recentFirst || groups.size < 2) return groups
    return groups.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<VideoGroup>> {
                lastPlayedAt(it.value.key)
            }.thenBy { it.index },
        )
        .map { it.value }
}
