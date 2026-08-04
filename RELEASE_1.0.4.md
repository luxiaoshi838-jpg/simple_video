# 简播 1.0.4

- MP4、TS、M4V、MKV 等常用格式恢复 Media3 播放。
- RM、RMVB、AVI、FLV 等格式继续使用 LibVLC；Media3 失败时自动回退 LibVLC。
- 分组内视频预览图加入 24 MB 内存 LRU 与本地磁盘缓存，避免滚动时重复抽帧或消失。
- 启动图标使用用户上传原图并放大裁边，去除黑色留边。
- 保留 ARM64 / ARMv7 分架构轻量 APK。
