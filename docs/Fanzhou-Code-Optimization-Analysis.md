# 泛舟 (Fanzhou) 深度代码检测与架构性能优化建议

> **检测时间**：2026-10-15  
> **代码规模**：55+ 个 Kotlin 源文件，约 15,800 行代码  
> **涉及核心模块**：双播放引擎（ExoPlayer / MPV）、云盘与文件共享（115/123/Quark/Aliyun/GDrive/WebDAV/SMB）、数据持久化与配置同步、Compose UI 响应层  

---

## 目录
1. [总体检测概览](#一总体检测概览)
2. [维度一：双播放内核架构解耦与重构（高收益）](#维度一双播放内核架构解耦与重构高收益)
3. [维度二：网络安全与 SSL 证书架构分级（安全合规）](#维度二网络安全与-ssl-证书架构分级安全合规)
4. [维度三：数据持久化瓶颈与数据库演进（稳定性）](#维度三数据持久化瓶颈与数据库演进稳定性)
5. [维度四：媒体与并发内存/资源管理（性能与健壮性）](#维度四媒体与并发内存资源管理性能与健壮性)
6. [维度五：Compose 渲染与计算热点优化（流畅度）](#维度五compose-渲染与计算热点优化流畅度)
7. [维度六：构建打包与 R8 优化（包体与冷启动）](#维度六构建打包与-r8-优化包体与冷启动)
8. [建议实施路线图与优先级矩阵](#八建议实施路线图与优先级矩阵)

---

## 一、总体检测概览

泛舟经过近期的一系列功能迭代（多云盘挂载、双核播放、画质记忆、AB循环、手势增强、备份同步二级架构、文件夹深度统计缓存），功能矩阵已经非常成熟完备。

通过对全量源码的静态检测与执行路径剖析，项目整体代码风格凝练高效，但也积累了**代码重复度高、单文件过大、SharedPreferences 过度使用、网络安全配置过于宽松**等可以进一步打磨优化的技术点。针对这些方面进行系统化改造，将使应用的稳定性、安全性与可维护性提升到一个全新的高度。

---

## 维度一：双播放内核架构解耦与重构（高收益）

### 1. 现状剖析
- [`PlayerActivity.kt`](file:///home/yun/fanzhou/app/src/main/java/com/kevin/fanzhou/player/PlayerActivity.kt) (2,442 行) 与 [`MpvPlayerActivity.kt`](file:///home/yun/fanzhou/app/src/main/java/com/kevin/fanzhou/player/MpvPlayerActivity.kt) (2,315 行) 两者代码总和达 4,750+ 行。
- **高重复度（> 60%）**：
  - 手势体系：单点显示/隐藏控制栏、双击快进快退/播放暂停、长按2倍速、滑动调节进度/亮度/音量；
  - 控制栏 UI 布局：顶部标题、画质提示、速度指示、电量时间、锁屏按钮、画质/音轨/字幕/跳过弹窗绑定；
  - 核心业务逻辑：跳过片头片尾控制器 (`PlayerSkipController`)、AB循环面板 (`AbLoopManager`)、断点记忆与防清零逻辑、云端与历史记录上报、画质记忆判定、在线字幕挂载等。
- **痛点与风险**：
  - 每次播放逻辑改动（例如最近的起播断点策略调整、AB循环弹窗改动），必须在两个文件手工同步修改两遍；
  - 极易出现“改了 Exo 漏了 MPV”的行为分叉和隐蔽 Bug。

### 2. 优化建议方案
采用 **“委托组件化模式”** 或 **“抽象公共基类模式”**：
1. **组件化抽离**：
   - `PlayerGestureHandler`：统一处理手势检测、触摸滑动拦截与屏幕长按快进；
   - `PlayerUiController`：统一管理控制器淡入淡出、顶部/底部控制栏事件交互；
   - `PlayerSessionTracker`：统一管理断点保存、历史防覆盖、Jellyfin 进度上报与最近播放更新。
2. **重构效果**：
   - `PlayerActivity` 与 `MpvPlayerActivity` 仅保留各自内核独特的初始化、解码器切换、音频 Track 解析与 Native 回调；
   - 两个核心 Activity 代码量预计均可缩减至 600~800 行，消除约 2,000 行重复代码，后续功能演进只需改动一处。

---

## 维度二：网络安全与 SSL 证书架构分级（安全合规）

### 1. 现状剖析
- 在 [`Repo.kt`](file:///home/yun/fanzhou/app/src/main/java/com/kevin/fanzhou/data/Repo.kt#L23-L34) 中：
  ```kotlin
  val trustAll = object : X509TrustManager { ... }
  val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
  http = OkHttpClient.Builder()
      .sslSocketFactory(ssl.socketFactory, trustAll)
      .hostnameVerifier { _, _ -> true }
  ```
- **痛点与风险**：
  - `Repo.http` 是全局共享的 OkHttpClient 单例，应用中所有的网络请求均走此通道；
  - 这意味着不仅自签证书的私有 NAS 豁免了校验，**公网网盘（115、阿里云盘、夸克、Google Drive 等）的登录令牌、Cookie、账户密码以及 TMDB 请求，均在全局信任任何证书与域名的状态下传输**；
  - 用户在公共 Wi-Fi 或不安全网络环境下，存在被中间人攻击（MITM）窃取网盘 Cookie 与敏感 Token 的风险；
  - 另外，[`ConfigSync.kt`](file:///home/yun/fanzhou/app/src/main/java/com/kevin/fanzhou/data/ConfigSync.kt#L68) 中的 WebDAV 密码、SSH 密码等以明文形式直接保存在标准 SharedPreferences XML 中。

### 2. 优化建议方案
1. **OkHttpClient 双通道分离**：
   - `secureHttp`（默认）：严格按照系统 CA 证书与标准 TLS 握手，所有公网网盘、TMDB、在线字幕、Coil 均使用该客户端；
   - `insecureLanHttp`：仅对局域网私有 IP（`192.168.*`, `10.*`, `172.16-31.*`, `.local`）或用户在设置中显式勾选了“信任自签名证书”的 NAS 媒体源单独注入忽略校验的 SSL 配置。
2. **敏感凭据加密存储**：
   - 使用 Android Jetpack 的 `EncryptedSharedPreferences`（结合 Android Keystore）保存用户网盘密码、WebDAV 登录凭据与 SSH 私钥，避免在 Root 设备或备份转存中明文泄露。

---

## 维度三：数据持久化瓶颈与数据库演进（稳定性）

### 1. 现状剖析
- [`Store.kt`](file:///home/yun/fanzhou/app/src/main/java/com/kevin/fanzhou/data/Store.kt#L14-L283) 采用 SharedPreferences 维护 `positions`（播放断点）、`fanzhou`（配置与网盘收藏/最近播放）、`tmdb_cache`，以及新增的 `folder_stat_cache`：
  - 播放视频时，定时上报或退出会触发 `saveProgress`，写入 `key`, `raw_pos_$key`, `dur_$key`, `fin_$key` 等多个字段；
  - Android SharedPreferences 机制是：**内存中保留完整 Map，只要有字段更新，执行 `apply()` 时需将整个 XML 完整序列化写入磁盘**；
  - 当用户使用半年或一年，观看数百上千部剧集和网盘视频后，`positions.xml` 会累积海量条目，频繁写入造成不必要的存储 I/O 抖动；
  - 且目前 `positions` 和缓存完全没有自动清理与老化淘汰机制。

### 2. 优化建议方案
1. **短期优化（低风险、立竿见影）**：
   - 增加**轻量淘汰巡检**：每周或每次应用启动时，扫描 `positions` 与 `folder_stat_cache`，对超过 90 天未访问的记录或超过 1,000 条上限的旧数据进行自动裁剪（保持在 500 条以内）。
2. **中长期演进（推荐架构路线）**：
   - 引入 **Room (SQLite)** 数据库框架，建立三张核心表：
     - `playback_history`：记录视频路径、媒体源 ID、起播时间、断点毫秒、总时长、完播标记、更新时间（加联合索引）；
     - `folder_stats`：存储文件夹统计；
     - `cloud_favorites`：替代原先存大 JSON 字符串的方式；
   - 优势：原生支持高效分页、按时间排序提取最近播放、支持原子事务与精准字段更新，也为后续规划的“Go 增量同步 API”奠定标准化数据库结构。

---

## 维度四：媒体与并发内存/资源管理（性能与健壮性）

### 1. 现状剖析
1. **缩略图缓存未设磁盘配额**：
   - [`VideoThumbnails.kt`](file:///home/yun/fanzhou/app/src/main/java/com/kevin/fanzhou/cloud/VideoThumbnails.kt#L44-L53) 将网盘视频帧截屏保存在 `cacheDir/video_thumbs` 下，采用 MD5 命名，但没有任何磁盘容量限制；长期使用后，成千上万张视频截图可能占用数 GB 缓存空间。
2. **SMB 本地代理线程池无上限**：
   - [`LocalProxy.kt`](file:///home/yun/fanzhou/app/src/main/java/com/kevin/fanzhou/player/LocalProxy.kt#L19) 使用了 `Executors.newCachedThreadPool()`；
   - 在 MPV 播放高码率 SMB 视频并频繁快速 Seek 或并发 Range 请求时，`CachedThreadPool` 会无上限创建新线程和 Socket 连接，容易瞬间拉高系统负载和内存消耗。
3. **未受控的协程调用**：
   - `PlayerActivity.kt` 第 784 行存在 `kotlinx.coroutines.GlobalScope.launch` 调用（被编译器标记为 Delicate API），不受生命周期监督。

### 2. 优化建议方案
1. **缩略图磁盘 LRU 限制**：
   - 为 `video_thumbs` 增加最大磁盘配额（如 150MB 或 1,000 张）；每次生成新图时若超过限额，按文件最后修改时间自动清理最旧的 20% 缓存。
2. **LocalProxy 线程池有界化**：
   - 将 `newCachedThreadPool()` 改为定长有界线程池（如 4~8 线程，配置 `LinkedBlockingQueue`），既保证流畅并发，又杜绝线程风暴。
3. **规范化 ApplicationScope**：
   - 在 `App` 或 `Repo` 中建立统一的 `val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`，淘汰所有 `GlobalScope`。

---

## 维度五：Compose 渲染与计算热点优化（流畅度）

### 1. 现状剖析
1. **自然排序计算冗余 (`WebDavClient.naturalKey`)**：
   - [`CloudBrowser.kt`](file:///home/yun/fanzhou/app/src/main/java/com/kevin/fanzhou/ui/CloudBrowser.kt#L319-L330) 排序时：
     ```kotlin
     compareBy<CloudFile> { it.modified }.thenBy { WebDavClient.naturalKey(it.name) }
     ```
   - 每次目录重排，对 N 个条目执行 $O(N \log N)$ 次比较，每次比较都执行 `Regex("\\d+").replace(...)` 正则匹配和字符串拼接，在数百条目时会反复创建大量临时对象，增加 GC 压力。
2. **TMDB 搜刮未缓存负向匹配**：
   - [`Tmdb.kt`](file:///home/yun/fanzhou/app/src/main/java/com/kevin/fanzhou/data/Tmdb.kt#L36-L58) 在未找到海报或接口异常时返回 `null`，并未向缓存中写入负向标记；导致列表在上下快速滑动时，对无结果的文件反复通过并发信号量向 TMDB 发起重复请求。
3. **首页网络请求缺乏内存 TTL 缓存**：
   - [`HomeScreen.kt`](file:///home/yun/fanzhou/app/src/main/java/com/kevin/fanzhou/ui/HomeScreen.kt#L68) 返回首页或重新触发时，每次都向 Jellyfin 发起 9+ 个并发 HTTP 请求，缺少 3~5 分钟的内存新鲜期保护。

### 2. 优化建议方案
1. **排序键预计算或惰性缓存**：
   - 在 `CloudFile` 中添加 `val naturalKey: String by lazy { WebDavClient.naturalKey(name) }`，或者在排序前预计算映射，实现零正则开销极速排序。
2. **TMDB 负向缓存机制**：
   - 当 TMDB 接口返回未找到或搜刮失败时，向 `tmdb_cache` 存入空标记 `""` 并记录时间戳（如 7 天有效期），避免无结果条目在滚动时无限次重查。
3. **首页数据加设 TTL 缓存**：
   - `HomeScreen` 引入 3 分钟有效期的内存快照，用户在短时间内返回首页无需重新拉取全部媒体库，下拉刷新时才强制穿透。

---

## 维度六：构建打包与 R8 优化（包体与冷启动）

### 1. 现状剖析
- [`app/build.gradle.kts`](file:///home/yun/fanzhou/app/build.gradle.kts#L31-L36) 中：
  ```kotlin
  buildTypes {
      release {
          isMinifyEnabled = false
          signingConfig = signingConfigs.getByName("release")
      }
  }
  ```
- **现状分析**：
  - 目前 Release APK 的体积约为 39MB（主要由 `libmpv` 和 `ffmpeg-decoder` 的动态库组成）；
  - Java/Kotlin 字节码部分完全未开启 R8 优化（代码混淆、无用代码剥离、函数内联均被关闭）；
  - 依赖库中包含了 `media3` 全家桶、`smbj`、`bouncycastle` 等大型类库，其中大量未调用的类与方法依然打包在 APK 中。

### 2. 优化建议方案
1. **逐步引入 R8 优化**：
   - 编写精细化的 `proguard-rules.pro`，为 `kotlinx.serialization`、`libmpv`、`media3`、`smbj`、`bouncycastle` 声明完整的 Keep 规则；
   - 开启 `isMinifyEnabled = true` 与 `isShrinkResources = true`；
   - 预期效果：DEX 字节码体积可缩小 20%~35%，方法数减少，提升 Android 运行时类加载速度和冷启动效率。

---

## 八、建议实施路线图与优先级矩阵

| 优先级 | 优化项目 | 涉及模块/文件 | 预期收益 | 改造难度 |
| :--- | :--- | :--- | :--- | :--- |
| **P0 (高收益/快见效)** | **排序自然键预计算** | `CloudBrowser.kt`, `CloudDrive.kt` | 列表滑动与排序彻底消除正则 GC 抖动 | 低（0.5 人天） |
| **P0 (高收益/快见效)** | **TMDB 负向结果缓存** | `Tmdb.kt` | 彻底避免无海报文件的并发网络死循环 | 低（0.5 人天） |
| **P0 (高收益/快见效)** | **缩略图磁盘配额清理** | `VideoThumbnails.kt` | 杜绝长期使用后应用缓存无底线膨胀 | 低（0.5 人天） |
| **P0 (高收益/快见效)** | **LocalProxy 线程池有界化** | `LocalProxy.kt` | 防止播放 SMB 视频快速拖动引发线程风暴 | 低（0.5 人天） |
| **P1 (核心质量提升)** | **双播放内核逻辑抽取** | `PlayerActivity.kt`, `MpvPlayerActivity.kt` | 消除 2000+ 行重复代码，杜绝播放器双端行为不一致 Bug | 中（2~3 人天） |
| **P1 (核心质量提升)** | **网络 SSL 双通道分级** | `Repo.kt`, `CloudDrive.kt` | 保护用户公网网盘与敏感 Token，兼顾内网 NAS 便捷性 | 中（1 人天） |
| **P1 (核心质量提升)** | **播放进度定期老化淘汰** | `Store.kt` | 防止 SharedPreferences positions.xml 无限累积膨胀 | 低（0.5 人天） |
| **P2 (架构长期演进)** | **Room 数据库架构迁移** | `data/db/*`, `Store.kt` | 支持复杂查询与事务，无缝对接未来 Go 同步 API 架构 | 较高（3~4 人天） |
| **P2 (架构长期演进)** | **Release R8 开启与混淆规则** | `build.gradle.kts`, `proguard-rules.pro` | 优化代码结构、瘦身 DEX、加快冷启动加载 | 中（1~2 人天） |

---
*本报告已自动同步至 NAS 目录：`fnos:/vol2/1000/share/app/agy/Fanzhou-Code-Optimization-Analysis.md`*
