package com.luxiaoshi.jianbo.player

enum class PlaybackOrientationMode {
    ADAPTIVE,
    PORTRAIT,
    LANDSCAPE,
}

internal fun nextPlaybackOrientationMode(mode: PlaybackOrientationMode): PlaybackOrientationMode =
    when (mode) {
        PlaybackOrientationMode.ADAPTIVE -> PlaybackOrientationMode.PORTRAIT
        PlaybackOrientationMode.PORTRAIT -> PlaybackOrientationMode.LANDSCAPE
        PlaybackOrientationMode.LANDSCAPE -> PlaybackOrientationMode.ADAPTIVE
    }

internal fun orientationTarget(
    mode: PlaybackOrientationMode,
    naturalLandscape: Boolean?,
): Boolean? = when (mode) {
    PlaybackOrientationMode.ADAPTIVE -> naturalLandscape
    PlaybackOrientationMode.PORTRAIT -> false
    PlaybackOrientationMode.LANDSCAPE -> true
}
