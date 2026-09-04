# 简播 1.0.10

- 增加“微信隐藏视频扫描”：Android 11+ 由用户显式授予所有文件访问后，深度扫描共享存储中的 `Android/media/com.tencent.mm` 与旧版 `Tencent/MicroMsg`，目录名以 `.` 开头也不会跳过。
- 深度扫描只补充 MediaStore 中不存在的文件，避免与正常自动扫描重复；不扫描 `Android/data`，也不尝试突破 Android 的应用私有存储边界。
- 已知视频扩展名不设置文件大小上限；对于无扩展名或临时扩展名的大文件，增加 MP4/MOV、Matroska/WebM、AVI、FLV 文件头识别，以覆盖微信可能采用的隐藏/临时命名。
- 修正文件夹视觉：主列表左侧 `Folder` 图标本身明确使用主题主色，不再显示为黑色；同时撤销 1.0.9 对整个浅色主题前景色的过宽修改，避免影响其他文字和图标。
- 正式版本升级为 `versionName=1.0.10`、`versionCode=11`；包名继续为 `com.luxiaoshi.jianbo`，用于覆盖升级 1.0.9。
