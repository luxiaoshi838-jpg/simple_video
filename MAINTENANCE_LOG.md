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
9. 待完成验证项：GitHub Actions Release 构建、APK 包名/版本解析、签名证书指纹核对、覆盖升级条件核对。完成后继续追加本日志。
