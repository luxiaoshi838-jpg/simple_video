# 简播（simple_video）

简播是一款 Android 本地视频播放器。它读取系统媒体库中的视频并按所在文件夹自动分组，也支持通过系统文件夹选择器手动导入指定文件夹。

## 已实现功能

- 自动扫描手机媒体库视频，按文件夹名称分组。
- 手动导入文件夹，并长期保留系统授予的读取权限。
- 长按进入批量管理；移除分组只隐藏应用内记录，绝不删除本地视频。
- 全屏沉浸播放，画面按横竖比例自适应。
- 左半屏上下滑动调亮度，右半屏上下滑动调媒体音量。
- 倍速：0.5、0.75、1、1.25、1.5、1.75、2、2.5、3 倍。
- 上一个、下一个；播放时按钮自动隐藏；中央双击暂停或继续。
- 视频可按 90°循环翻转；横屏使用传感器方向，可随手机进入反向横屏。

## 技术栈

Kotlin、Jetpack Compose、Material 3、AndroidX Media3 ExoPlayer、MediaStore、Storage Access Framework。

## 构建

推荐 Android Studio + JDK 17。命令行可运行：

```bash
gradle :app:assembleDebug
gradle :app:assembleRelease
```

GitHub Actions 会构建调试 APK 和未签名 Release APK。

## 正式签名

真实密钥不能进入公开仓库。将密钥保存在本机 `signing/`，把 `keystore.properties.example` 复制为 `keystore.properties` 并填写真实值。后续所有正式更新必须继续使用同一密钥。

## 权限

- Android 13 及以上：`READ_MEDIA_VIDEO`
- Android 12 及以下：`READ_EXTERNAL_STORAGE`
- 手动导入使用系统文件夹授权，不申请“所有文件访问权限”。

## 开源参考

见 [`docs/REFERENCE.md`](docs/REFERENCE.md)。项目为独立实现，不复制 GPL 项目源码。

## 许可证

MIT
