# 开源参考与采用边界

本项目为独立实现，不直接复制下列项目源码。

- **Next Player**：参考亮度/音量手势、简洁播放器交互和本地媒体选择思路。其许可证为 GPL-3.0，因此本项目只参考公开功能与交互，不复制实现代码。
- **Just (Video) Player**：参考轻量化播放器设计和 Media3/ExoPlayer 的使用边界。其代码采用 Unlicense。
- **mpv-android**：参考横竖屏、反向横屏和高级播放交互。其播放器不是可直接导入的 Android AAR，本项目不引入其原生解码链。
- **AndroidX Media3 官方文档**：作为播放内核、播放列表、倍速和生命周期管理的直接技术依据。

链接：

- https://github.com/anilbeesetti/nextplayer
- https://github.com/moneytoo/Player
- https://github.com/mpv-android/mpv-android
- https://developer.android.com/media/media3
