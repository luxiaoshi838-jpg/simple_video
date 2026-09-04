# 简播维护日志

## 2026-09-04 · 1.0.9 本地视频识别与文件夹视觉修复

1. 用户明确目标软件为“简播”，确认 GitHub 仓库为 `luxiaoshi838-jpg/simple_video`；此前误查到 `simplereader-public` 的内容未写入简播仓库。
2. 核对 `main` 基线：提交 `60345eb68592a0886e8d6225b78577e1924c6c3b`，版本 `1.0.8` / `versionCode=9`，包名 `com.luxiaoshi.jianbo`。
3. 从用户 Google Drive 的“签名文件”文件夹读取 `简播签名.zip`，确认其中为简播正式签名材料。安全约束：密钥、密码、keystore 不提交到公开仓库，日志不记录密码。
4. 建立工作分支 `agent/v1.0.9-media-scan-ui`，所有源码修改在该分支进行。
5. 媒体识别审计：原实现自动扫描仅查询 `MediaStore.Video`；增加 `MediaStore.Files` 兜底扫描，使用 `MEDIA_TYPE`、MIME 与常见视频扩展名识别，并用 `media:<id>` 与原扫描结果去重。
6. 兜底扫描保留 Android scoped-storage 边界，不加入 `MANAGE_EXTERNAL_STORAGE`；系统不可见的应用私有目录不绕过权限读取。
7. 文件夹视觉审计：原文件夹 Material 图标使用默认 `onSurface`，浅色主题下呈接近黑色；将浅色 `onSurface` / `onSurfaceVariant` 调整为蓝灰色，并覆盖 Android 12+ 动态浅色主题对应前景色。
8. 覆盖升级约束：保持 applicationId `com.luxiaoshi.jianbo`，版本提升到 `1.0.9` / `versionCode=10`；最终 APK 必须继续使用 Google Drive 中同一简播正式证书签名。
9. 第一次 GitHub Actions Release 构建（run `33852826797`）失败。失败发生在 `mergeReleaseNativeLibs` 的依赖解析阶段，不是新扫描代码编译错误；原因是本次编辑 `build.gradle.kts` 时将原来的 `androidx.compose.material3:material3` 误写成 `androidx.material3:material3`，导致 Gradle 查找不存在的空版本依赖。已立即恢复为正确坐标，并记录为本次操作错误，禁止后续重复。
10. 修正后再次执行 GitHub Actions Release 构建（run `33853055020`，源码提交 `e407f440c2524b43b6aec716c314dbbc0e011924`），构建与 unsigned APK artifact 上传全部成功；正式编译步骤耗时约 3 分 28 秒，未达到 5 分钟卡死检查阈值。
11. 从成功的 GitHub Actions run 下载 artifact `jianbo-unsigned-release-apk`（artifact id `9929198239`），其中包含 `arm64-v8a` 与 `armeabi-v7a` 两个 Release APK。
12. 为避免把正式私钥提交到公开 GitHub，最终签名在安全本地环境完成：签名材料只从 Google Drive 读取；签名工具使用本仓库此前 GitHub Actions 已导出的 Android 官方 `apksigner` artifact（artifact id `8878305348`）。签名密码未输出、未写日志、未上传 GitHub。
13. 签名证书核对：alias `jianbo-release`；SHA-256 指纹 `BE:84:CF:EE:C9:68:29:21:47:42:79:D1:31:54:D5:AA:F5:F7:79:3A:49:F6:9E:2C:7A:31:D3:4D:F6:67:7F:4C`。两个 APK 均通过 APK Signature Scheme v2、v3 验证。
14. Android 官方 `zipalign` 旧 artifact 在当前本地环境首次调用时因缺少 `libc++.so` 无法启动；未继续依赖该二进制。随后直接检查两个 Gradle Release APK 的 ZIP 条目偏移，全部未压缩条目均满足 4 字节对齐（0 个不合格条目），签名后再次核对无异常。
15. 对两个最终 APK 的二进制 AndroidManifest 进行解析核验：package 均为 `com.luxiaoshi.jianbo`，versionName 均为 `1.0.9`，versionCode 均为 `10`。结合正式证书未变、versionCode 高于 1.0.8 的 9，满足当前正式简播 1.0.8 的覆盖升级条件。
16. 最终 APK SHA-256：arm64-v8a = `bdc2fd265f20252b1563d23c2a9a57f10993917383f97b911996467dbef41893`；armeabi-v7a = `cb6bd17b1b6663aa0f899ffe266a00b3a716761b263ac438c4724de98ffe56a5`。
17. 微信视频识别能力边界：本版可补充发现已进入 Android 共享媒体数据库、但未正常出现在 `MediaStore.Video` 分类中的本地视频；Android 系统仍不允许第三方应用越权读取微信私有目录，因此私有缓存文件不会通过本修复绕过系统权限直接读取。
18. 创建 GitHub PR `#12`（`agent/v1.0.9-media-scan-ui` → `main`），核对分支相对 main 为 ahead 3 / behind 0；PR 可合并后使用 merge 方式完成合并。main 合并提交为 `39cbb56c5700709443f797a38bea3a19b80f515e`。本次最终日志补记使用 `[skip ci]`，避免在已通过 Release 构建后仅因日志文本再次触发重复构建。
