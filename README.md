<div align="center">

# 泛舟 (Fanzhou)

**为 Android 量身定制的双内核全能影音播放器**

*兼顾移动端手势操控与家庭大屏（Android TV / 投影仪）沉浸体验*

[![Release](https://img.shields.io/github/v/release/jxdxs/fanzhou?color=blue&logo=github)](https://github.com/jxdxs/fanzhou/releases)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Dual Engine](https://img.shields.io/badge/Player-ExoPlayer%20%7C%20libmpv-orange)](https://github.com/mpv-player/mpv)

[功能特性](#-功能特性) • [双内核架构](#-旗舰双播放内核) • [支持的媒体源](#-全能媒体库与云盘支持) • [操作手势](#-手势交互与快捷操作) • [下载安装](#-下载与安装) • [编译构建](#-编译与构建)

</div>

---

## 📖 简介

**泛舟（Fanzhou）** 是一款基于 Kotlin 与现代 Jetpack Compose 构建的高性能 Android 媒体播放器。

不同于市面上常见的单一内核播放器，泛舟深度集成了 **Media3 ExoPlayer** 与原生 **libmpv (含完整 FFmpeg 解码矩阵)** 两个旗舰播放引擎。针对国内常见的云盘观影场景、家庭 NAS 私有云媒体库及高码率 4K HDR 原盘播放，泛舟提供了强大的格式兼容性、智能字幕匹配、精准 AB 片段循环、专属片头跳过以及多端加密同步能力。

---

## ✨ 功能特性

### 🎬 旗舰双播放内核
- **双引擎随心换**：默认根据片源智能调度，播放界面可一键秒级平滑切换 ExoPlayer 与 MPV 内核，记忆断点不中断。
- **高码率原盘极致解码**：通过 libmpv 完美支持 AV1、HEVC 10-bit、HDR10、Dolby Vision、高帧率原盘以及复杂的 ASS/SSA 特效字幕渲染。
- **夜间人声对白增强**：针对影视中背景环境音与背景音乐嘈杂导致人声听不清的问题，内置人声中频提取与音频动态增益算法。

### ☁️ 全能媒体库与主流网盘聚合
- **主流网盘直连**：原生支持阿里云盘、115 网盘、夸克网盘、百度网盘、123 网盘、Google Drive 等（支持二维码登录与账密授权）。
- **家庭局域网与私有云协议**：深度集成 Jellyfin / Emby 影视库，无缝支持 WebDAV、SMB 2/3 及本地存储挂载。
- **文件夹画质持久记忆**：长按剧集目录可持久化锁定偏好画质（原画 / 4K / 1080P / 720P / 480P），支持跨集自适应记忆与下集预解析，杜绝连播卡死。
- **视频帧缩略图自动提取**：无云端海报的视频文件，后台多线程抽取高保真视频帧作为封面并本地缓存，海报墙美观精致。

### 🔄 AB 片段循环播放
- **区间自定义与精准微调**：支持一键将当前播放进度设为 A 点或 B 点，提供 ±1 秒 / ±5 秒精准单步微调及长按连续快速步进。
- **多段命名与循环管理**：单个视频支持创建并持久化存储多个 AB 片段；支持长按片段呼出精致操作菜单进行二次编辑。
- **防误触删除保护**：删除片段时提供包含起止时长与片段名的二次确认防护，杜绝误操作。

### ⏩ 智能跳过片头片尾 & 章节打点
- **单剧独立秒数记忆**：支持针对特定连续剧单独记忆跳过片头/片尾秒数，播放起播自动生效。
- **轻量微胶囊防误跳**：遇到片头时在左下角弹出轻量悬浮胶囊提示，支持手动点击一键跳过或撤销跳过。
- **内嵌章节（Chapters）标记**：自动解析 MKV / MP4 内置章节并在进度条上绘制打点标记，支持快速章节定位。

### 🔤 顶级字幕体验
- **三源智能在线搜索**：深度集成射手网(伪) assrt.net、SubDL 与 OpenSubtitles，支持中文字幕自动匹配与 API 连通性测试。
- **多维度字幕排版定制**：支持字幕字体大小、粗体、文字颜色、背景框、描边阴影、垂直位置及延迟时间（±0.1s）微调。
- **本地字幕智能挂载**：支持外挂 srt / ass / vtt / sub 字幕，本地字幕自动记忆关联。

### 🛡️ AES-256 密码学备份与多设备隔离
- **端到端强加密备份**：备份文件支持采用 `AES-256-GCM` 配合 `PBKDF2WithHmacSHA256`（65,536 次迭代加盐）加密导出，严防网盘 Token 与影视库凭据泄露。
- **多用户档案隔离（Profile）**：支持设定“个人”、“客厅电视”、“卧室”、“父母”等独立档案标识，备份文件与 WebDAV 路径自动隔离，防止家庭多人/多端互冲。
- **WebDAV 双向自动同步**：支持应用开启时自动比对更新，配置变更后 15 秒防抖自动上传。

### 📱 细节交互与大屏适配
- **对称画中画（PiP）**：实现横屏播放器与画中画悬浮窗的无缝互逆过渡，预计算显示区域，消除上下边缘黑边裁剪与拉伸变形。
- **全屏防误触锁**：常设右侧中位锁屏悬浮按钮，解锁前弹出紧凑二次确认弹窗，杜绝携带与误触。
- **断点起播与防归零保护**：历史视频打开时默认从头起播并弹出 10 秒倒计时悬浮窗（「上次播放至 xx:xx」），短时间内退出自动保留原断点，绝不误冲刷归零。
- **自适应动态大缓存**：支持 32MB ~ 512MB 播放缓存深度调控，结合设备 JVM 堆内存动态熔断机制，兼顾弱网流畅与防 OOM 闪退。

---

## ⚖️ 旗舰双播放内核对比

| 特性维度 | Media3 ExoPlayer | libmpv 原生引擎 |
| :--- | :--- | :--- |
| **主要优势** | 系统硬件解码、功耗极低、发热小、起播极快 | 极致格式兼容性、全能软解、色彩渲染卓越 |
| **推荐适用场景** | 常见流媒体（MP4/H.264/H.265 8-bit）、网络波动场景 | 4K HDR、AV1、HEVC 10-bit 原盘、复杂特效 ASS 字幕 |
| **音频处理** | 结合 Android AudioTrack，支持夜间对白增益 | 支持音频规格直通与多通道软混音 |
| **切换方式** | 控制栏底部设置菜单中可一键平滑互切，自适应回退保护 |

---

## 🖐️ 手势交互与快捷操作

在全屏播放状态下，屏幕支持丰富直观的手势控制：

```
                ┌──────────────────────────────────┐
                │ ☀ 亮度调节        🔊 音量调节    │
                │ (屏幕左侧上下滑)   (屏幕右侧上下滑) │
                │                                  │
                │        ⏪ 双击快退   ⏩ 双击快进   │
                │              ❚❚ 双击暂停/播放    │
                │                                  │
                │      ◄─── 左右水平滑动拖动进度 ───► │
                │                                  │
                │        [ 长按任意区域: 2× 快进 ]    │
                └──────────────────────────────────┘
```

- **单击画面**：显示 / 隐藏控制栏（常驻 10 秒无操作自动渐隐）。
- **双击屏幕**：左侧 1/3 快退，右侧 1/3 快进，中间区域暂停/播放。
- **上下滑动**：左半屏上下滑动调节屏幕亮度，右半屏上下滑动调节媒体音量。
- **左右滑动**：全屏任意位置水平滑动快速微调视频进度。
- **长按屏幕**：触发 2× 倍速快进，松手即刻恢复原播放速度。

---

## 📥 下载与安装

进入项目的 [Releases 页面](https://github.com/jxdxs/fanzhou/releases) 下载最新版本的 Release APK：

- 每个版本的 Release APK 均由 GitHub Actions 自动化流水线使用统一发布密钥签名，可直接覆盖安装旧版本升级。
- 支持 Android 8.0 (API 26) 及更高版本的手机、平板、折叠屏、车机、Android TV 电视盒子与投影仪。

---

## 🔨 编译与构建

### 1. 构建环境依赖
- **操作系统**：Linux / macOS / Windows
- **JDK 版本**：Java 17 (推荐 Eclipse Temurin 17)
- **Android SDK**：API 35 (Build-Tools 35.0.0)
- **NDK**：26.1.10909125 (编译 libmpv / C++ JNI 组件)
- **Gradle**：8.9+

### 2. 本地快速编译 Debug 版本
```bash
# 克隆代码仓库
git clone https://github.com/jxdxs/fanzhou.git
cd fanzhou

# 执行单元测试
./gradlew testDebugUnitTest

# 编译 Debug APK
./gradlew assembleDebug
```
编译产物输出位置：`app/build/outputs/apk/debug/app-debug.apk`。

### 3. Release 版本与 GitHub Actions 构建
本仓库默认通过 GitHub Actions（`.github/workflows/build.yml`）进行自动化构建：
- 当推送至 `main` 分支或发布 `v*` 标签时，自动触发构建并输出 APK Artifact；
- 生产环境签名材料（`FANZHOU_KEYSTORE_BASE64`、`FANZHOU_KEY_PASSWORD` 等）由 GitHub Secrets 安全注入，无需在公开仓库提交私有密钥文件。

---

## 🔒 隐私与安全说明

1. **凭证脱敏安全**：本公开仓库的源代码已全面剔除内置的第三方开放平台登录密钥。在自行编译构建时，若需使用各网盘开放平台登录能力，请在设置中配置属于您个人的 Client ID / Secret。
2. **离线配置安全**：使用「配置备份」功能时，强烈建议勾选启用 **AES-256 加密密码**。加密后的备份文件可在不安全的网络环境或云盘存储中高枕无忧，密码未输入前任何第三方皆无法解析明文凭据。

---

## 💡 技术栈与开源生态致谢

泛舟的诞生离不开以下优秀的开源项目与社区生态：

- **UI 界面**：[Jetpack Compose](https://developer.android.com/jetpack/compose) & Material Design 3
- **播放核心**：[androidx.media3 ExoPlayer](https://github.com/androidx/media) & [mpv-player / libmpv](https://github.com/mpv-player/mpv)
- **多媒体编解码**：[FFmpeg](https://ffmpeg.org/)
- **网络与序列化**：[OkHttp](https://square.github.io/okhttp/) & [Kotlinx.Serialization](https://github.com/Kotlin/kotlinx.serialization)
- **字幕源支持**：[射手网(伪) assrt](https://assrt.net/)、[SubDL](https://subdl.com/)、[OpenSubtitles](https://www.opensubtitles.com/)

---

<div align="center">
  <sub>Made with ❤️ for pure playback experience.</sub>
</div>
