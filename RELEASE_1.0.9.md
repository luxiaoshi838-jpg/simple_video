# 简播 1.0.9

- 自动扫描在原有 `MediaStore.Video` 之外增加 `MediaStore.Files` 视频兜底扫描，并按媒体 ID 去重，用于补充聊天应用保存到共享存储但未正常进入 Video 分类的本地视频。
- 兜底扫描仍遵守 Android 媒体权限与 scoped storage，不申请“所有文件访问权限”；系统不向第三方应用暴露的私有目录仍需由系统允许的 SAF 路径处理。
- 浅色界面的文件夹/卡片前景色改为蓝灰色，不再使用接近纯黑的默认效果。
- 正式版本升级为 `versionName=1.0.9`、`versionCode=10`；包名继续为 `com.luxiaoshi.jianbo`，用于覆盖升级当前 1.0.8。
