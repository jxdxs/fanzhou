# Fanzhou 播放器

Fanzhou 是一个 Android 本地/网络媒体播放器（包名 `com.kevin.fanzhou`），基于 Kotlin + Jetpack Compose 构建，内置 Media3 ExoPlayer 与 libmpv 两个播放内核，并集成 FFmpeg 解码能力。

## 功能

- 双播放内核：Media3 ExoPlayer 与 libmpv，可按片源切换
- 媒体源：Jellyfin、WebDAV、SMB、本地文件
- 云盘：115、阿里云盘、夸克、Google Drive 等
- 横屏播放底栏：播放/暂停、进度、倍速、字幕、音轨切换；竖屏保持简洁控制
- 字幕、音轨、多版本与播放进度等常用播放能力

## 下载

到 [Releases](https://github.com/jxdxs/fanzhou/releases) 下载最新 APK。Release 中的 APK 由 GitHub Actions 构建，并使用项目原发布密钥签名，可覆盖安装旧版本。

> 注意：本公开仓库的源码已移除内嵌的第三方登录凭据。公开构建不能使用默认方式登录 Google 云盘，如需使用请在 App 内填写自己的 Client ID / Secret；Jellyfin、WebDAV、SMB、115 等其他媒体源不受影响。

## 构建

本仓库通过 GitHub Actions 构建：推送到 `main` 或手动运行 **Build Fanzhou APK** workflow 即可生成 APK。

构建环境：JDK 17、Android SDK 35、Gradle 8.9。

发布签名不存放在仓库中。Actions 构建时会从仓库 Secrets 注入签名材料：

- `FANZHOU_KEYSTORE_BASE64`
- `FANZHOU_KEYSTORE_PASSWORD`
- `FANZHOU_KEY_ALIAS`
- `FANZHOU_KEY_PASSWORD`

没有这些 Secrets 时，Release 构建会失败；这是为了避免把签名密钥公开在仓库里。

## 技术栈

Kotlin、Jetpack Compose、Media3 ExoPlayer 1.7.1、FFmpeg、libmpv。
