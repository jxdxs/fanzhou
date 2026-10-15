# 泛舟 (Fanzhou) 基于「Go API + SQLite + 配置文件」的同步方案设计

> **说明**：本文档为技术架构与落地执行方案，供技术评估与开发规划参考，**不属于版本发布更新**。

---

## 一、 方案背景与架构演进

当前泛舟（Fanzhou）采用的是**基于文件快照的双向备份**（通过本地导出、WebDAV/SSH/云盘上传全量 JSON 备份包）。这种方式虽然实现简单、通用性高，但在多设备日常使用中存在以下痛点：
1. **全量覆盖存在竞态冲突**：两台设备（如手机与平板）若交替观看不同剧集，全量上传容易产生覆盖丢失（Last-Write-Wins 覆盖整个文件而非单条进度）。
2. **高频进度同步网络开销大**：每次播放暂停或退出都上传几百 KB 的完整快照文件，不适合秒级或分钟级的高频断点续播。
3. **缺乏结构化检索与统计能力**：文件形式难以高效进行按时间、按媒体源、按设备维度的多端播放记录查询与合并。

针对以上问题，采用 **`Android App -> Go 语言轻量同步 API -> [SQLite 数据库 + 配置文件库]`** 的分层架构，是目前业界轻量级私有云同步的**黄金组合**。

```mermaid
graph TD
    subgraph 客户端 [多端设备 Client]
        A1["Android 手机 (Fanzhou)"]
        A2["Android 平板 / TV (Fanzhou)"]
        A3["其他终端 (未来的 Web/Desktop)"]
    end

    subgraph 同步网关 [Go 同步服务 (轻量驻留 NAS/VPS)]
        B["Go API Server (Gin / net/http)"]
        B1["Token 鉴权 / 设备识别"]
        B2["双向增量同步引擎 (Delta Sync)"]
        B3["快照版本化管理 (Snapshots)"]
    end

    subgraph 存储层 [持久化存储 Storage]
        C1[("SQLite 数据库")]
        C1_1["• 播放历史与断点记录 (高频增删改查)"]
        C1_2["• 设备在线状态与同步时间戳游标"]
        C2["配置文件库 (Config Storage)"]
        C2_1["• 多端通用配置 JSON (媒体源/设置)"]
        C2_2["• 历史快照版本归档 (.json.gz)"]
    end

    A1 <-->|"HTTPS/HTTP REST API (带 Token)"| B
    A2 <-->|"HTTPS/HTTP REST API (带 Token)"| B
    A3 <-->|"HTTPS/HTTP REST API (带 Token)"| B

    B --> B1
    B1 --> B2
    B1 --> B3

    B2 <-->|"WAL 模式高并发读写"| C1
    B3 <-->|"版本快照留存与回滚"| C2
```

---

## 二、 核心优势分析

1. **极致轻量，极低资源占用**：
   - Go 编写的 API 服务编译后为**单一可执行二进制文件**（无外部依赖），内存常驻仅 **10MB ~ 25MB**；
   - 极其适合部署在家用 NAS（如飞牛私有云 fnOS、群晖 Synology、威联通 QNAP）、软路由或极低配置的 VPS 上。
2. **高频数据与低频配置动静分离**：
   - **高频数据（播放进度）**：存入 **SQLite**，单条记录增量上报仅需几十字节 JSON，支持毫秒级多端断点实时无缝流转；
   - **低频配置（系统设置、网盘源、UI偏好）**：存入 **配置文件/快照库**，保留版本历史（如每日快照或近 10 次修改），支持一键无损回滚。
3. **纯 Go 零 CGO 编译，极简部署**：
   - 采用纯 Go 实现的 SQLite 驱动（`modernc.org/sqlite`），无需宿主机安装 GCC/glibc，交叉编译一次即可在 `x86_64`、`ARM64`、`ARMv7`（各品牌 NAS 硬件架构）直接运行。
4. **冲突精准解决（细粒度 Last-Write-Wins）**：
   - 每条播放进度绑定 `updated_at` 客户端更新毫秒时间戳；
   - SQLite 内部直接通过 `ON CONFLICT DO UPDATE WHERE excluded.updated_at > current.updated_at` 解决冲突，不同剧集更新互不影响，彻底杜绝多设备进度覆盖。

---

## 三、 服务端设计与规范 (Go Sync Server)

### 1. 技术栈选型
- **开发语言**：Go 1.22+
- **HTTP 框架**：Gin 或 Go 原生 `net/http`（推荐 Gin，路由清晰，自带中间件与 JSON 绑定）
- **数据库驱动**：`modernc.org/sqlite`（纯 Go 驱动，零 CGO，跨平台极其简便）
- **部署方式**：独立二进制运行（搭配 systemd）或官方极简 Docker 镜像（镜像体积 < 20MB）

### 2. SQLite 数据库结构设计

```sql
-- 开启 WAL 模式以支撑多设备并发读写
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 5000;

-- 1. 用户与认证表
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,               -- 用户 UUID
    username TEXT NOT NULL UNIQUE,     -- 用户名
    token TEXT NOT NULL UNIQUE,        -- 认证 API Key / Token
    created_at INTEGER NOT NULL        -- 创建时间 (毫秒时间戳)
);

-- 2. 播放历史与断点进度表 (核心高频表)
CREATE TABLE IF NOT EXISTS playback_progress (
    user_id TEXT NOT NULL,
    video_key TEXT NOT NULL,           -- 全局唯一视频 Key (如 cloud:sid:fileId 或 jf:sid:itemId)
    title TEXT NOT NULL,               -- 视频标题
    position_ms INTEGER NOT NULL,      -- 播放进度 (毫秒)
    duration_ms INTEGER NOT NULL,      -- 视频总时长 (毫秒)
    finished INTEGER NOT NULL DEFAULT 0,-- 是否已看完 (0: 未完, 1: 已看完)
    device_id TEXT NOT NULL,           -- 最后更新设备 (如 "Pixel 8", "MiPad 6")
    updated_at INTEGER NOT NULL,       -- 最后更新时间戳 (UTC 毫秒)
    PRIMARY KEY (user_id, video_key)
);
CREATE INDEX IF NOT EXISTS idx_progress_user_updated ON playback_progress(user_id, updated_at);

-- 3. 配置快照历史归档表
CREATE TABLE IF NOT EXISTS config_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    version INTEGER NOT NULL,          -- 版本流水号
    device_id TEXT NOT NULL,           -- 提交设备
    config_json TEXT NOT NULL,         -- 全量配置 JSON 内容
    checksum TEXT NOT NULL,            -- SHA256 校验哈希
    remark TEXT,                       -- 备份说明 ("手动上传" / "定时同步")
    created_at INTEGER NOT NULL        -- 创建时间戳 (UTC 毫秒)
);
CREATE INDEX IF NOT EXISTS idx_config_user_created ON config_snapshots(user_id, created_at DESC);
```

### 3. RESTful API 接口定义

| 接口端点 | 方法 | 说明 | 核心请求/响应参数 |
| :--- | :--- | :--- | :--- |
| `/api/v1/ping` | GET | 服务健康检查 | 响应：`{"status":"ok","time":1729000000000}` |
| `/api/v1/sync/progress` | POST | **双向增量同步播放进度** | 一次请求完成「客户端变更上报」与「云端最新增量下发」 |
| `/api/v1/config/backup` | POST | 备份当前全量配置快照 | 上传当前配置 JSON，服务端校验哈希并写入新快照 |
| `/api/v1/config/latest` | GET | 获取最新版本的配置 | 响应最新的配置内容及版本号 |
| `/api/v1/config/snapshots` | GET | 查询历史配置快照列表 | 返回历史快照的时间、版本、备注与大小 |
| `/api/v1/config/restore/:id`| GET | 拉取指定快照的完整配置 | 用于设置界面一键选定历史节点恢复 |

#### 双向增量同步核心接口：`POST /api/v1/sync/progress`
- **请求体（客户端 -> 服务端）**：
  ```json
  {
    "client_time": 1729000100000,
    "since": 1729000000000,
    "changes": [
      {
        "video_key": "cloud:115:file_123456",
        "title": "庆余年2 - 01.mkv",
        "position_ms": 1345000,
        "duration_ms": 2700000,
        "finished": false,
        "updated_at": 1729000095000
      }
    ]
  }
  ```
- **响应体（服务端 -> 客户端）**：
  ```json
  {
    "code": 0,
    "server_time": 1729000100050,
    "synced_keys": ["cloud:115:file_123456"],
    "updates": [
      {
        "video_key": "jf:fnos:item_8888",
        "title": "流浪地球2.mp4",
        "position_ms": 5400000,
        "duration_ms": 10380000,
        "finished": false,
        "device_id": "Pad-Office",
        "updated_at": 1729000080000
      }
    ]
  }
  ```
> **设计精粹**：通过单次 HTTP 往返即可同时完成“本地进度推送到云端”与“拉取其他设备在 `since` 之后产生的进度更新”，耗时仅需数十毫秒，流量消耗不足 1KB。

---

## 四、 Android 客户端改造方案 (Fanzhou App)

### 1. 设置界面入口接入
在设置页「备份同步」二级菜单中，扩充多后端选项卡：
`[WebDAV, SSH, 谷歌云盘, 专属服务]`
- 点击「专属服务」：
  - **连接配置**：
    - 服务端 URL：例如 `http://192.168.1.100:8088` 或 `https://fanzhou.your-nas.cn`；
    - 访问密钥（API Token）：管理员在 Go 服务端生成的唯一 Token；
    - 设备别名（如：`小米手机`、`客厅电视盒`）；
  - **同步策略开关**：
    - 「实时进度同步」（默认开启）：播放暂停、退出或切集时自动后台异步上报；
    - 「启动时拉取最新进度」（默认开启）：App 启动或从后台切回时自动触发增量拉取；
    - 「配置每日自动备份」：每天首次启动时自动向专属服务推送一份配置快照。

### 2. 本地同步引擎（`GoSyncEngine.kt`）实现方案
- **本地暂存与脏标记**：
  - 在 `Store.kt` 的播放进度变更入口（`saveProgress`）中，不仅写入本地 SharedPreferences，同时向轻量本地待同步队列追加该条记录及时间戳；
- **异步批处理上报**：
  - 使用 Kotlin 协程 `CoroutineScope(Dispatchers.IO)` 执行非阻塞 HTTP 上报；
  - 遇到弱网或离线时，队列暂存本地，待网络恢复后合并重发（带本地最后更新时间戳，绝不丢失最新进度）；
- **全量配置导入导出对接**：
  - 调用 `ConfigSync.exportConfigJson()` 生成结构化数据，直接对接 `POST /api/v1/config/backup`；
  - 恢复时拉取 `/api/v1/config/latest` 或指定快照，直接复用现有的安全解包与合并逻辑。

---

## 五、 服务端示例代码骨架 (Go)

```go
package main

import (
	"database/sql"
	"log"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	_ "modernc.org/sqlite"
)

type ProgressItem struct {
	VideoKey   string `json:"video_key" binding:"required"`
	Title      string `json:"title"`
	PositionMs int64  `json:"position_ms"`
	DurationMs int64  `json:"duration_ms"`
	Finished   bool   `json:"finished"`
	DeviceID   string `json:"device_id"`
	UpdatedAt  int64  `json:"updated_at" binding:"required"`
}

type SyncRequest struct {
	Since   int64          `json:"since"`
	Changes []ProgressItem `json:"changes"`
}

func main() {
	db, err := sql.Open("sqlite", "fanzhou_sync.db")
	if err != nil {
		log.Fatalf("无法连接 SQLite: %v", err)
	}
	defer db.Close()

	// 初始化建表与 WAL 优化
	initDatabase(db)

	r := gin.Default()

	// 简易 Token 鉴权中间件
	authGroup := r.Group("/api/v1", tokenAuthMiddleware(db))
	{
		// 增量同步端点
		authGroup.POST("/sync/progress", func(c *gin.Context) {
			userID := c.GetString("user_id")
			deviceID := c.GetHeader("X-Device-ID")
			var req SyncRequest
			if err := c.ShouldBindJSON(&req); err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
				return
			}

			now := time.Now().UnixMilli()

			// 1. 批量 Upsert 客户端上报的变更
			tx, _ := db.Begin()
			upsertStmt, _ := tx.Prepare(`
				INSERT INTO playback_progress (user_id, video_key, title, position_ms, duration_ms, finished, device_id, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT(user_id, video_key) DO UPDATE SET
					position_ms = excluded.position_ms,
					duration_ms = excluded.duration_ms,
					finished = excluded.finished,
					device_id = excluded.device_id,
					updated_at = excluded.updated_at
				WHERE excluded.updated_at > playback_progress.updated_at
			`)
			for _, item := range req.Changes {
				fin := 0
				if item.Finished { fin = 1 }
				upsertStmt.Exec(userID, item.VideoKey, item.Title, item.PositionMs, item.DurationMs, fin, deviceID, item.UpdatedAt)
			}
			upsertStmt.Close()
			tx.Commit()

			// 2. 查询 since 之后由其他设备产生的新变更
			rows, _ := db.Query(`
				SELECT video_key, title, position_ms, duration_ms, finished, device_id, updated_at
				FROM playback_progress
				WHERE user_id = ? AND updated_at > ? AND device_id != ?
			`, userID, req.Since, deviceID)
			defer rows.Close()

			var updates []ProgressItem
			for rows.Next() {
				var p ProgressItem
				var fin int
				rows.Scan(&p.VideoKey, &p.Title, &p.PositionMs, &p.DurationMs, &fin, &p.DeviceID, &p.UpdatedAt)
				p.Finished = (fin == 1)
				updates += []ProgressItem{p}
			}

			c.JSON(http.StatusOK, gin.H{
				"code":        0,
				"server_time": now,
				"updates":     updates,
			})
		})
	}

	log.Println("泛舟 Go 同步服务正在监听 :8088...")
	r.Run(":8088")
}

func initDatabase(db *sql.DB) {
	db.Exec("PRAGMA journal_mode = WAL;")
	db.Exec("PRAGMA synchronous = NORMAL;")
	db.Exec(`
		CREATE TABLE IF NOT EXISTS playback_progress (
			user_id TEXT NOT NULL,
			video_key TEXT NOT NULL,
			title TEXT NOT NULL,
			position_ms INTEGER NOT NULL,
			duration_ms INTEGER NOT NULL,
			finished INTEGER NOT NULL DEFAULT 0,
			device_id TEXT NOT NULL,
			updated_at INTEGER NOT NULL,
			PRIMARY KEY (user_id, video_key)
		);
		CREATE INDEX IF NOT EXISTS idx_progress_updated ON playback_progress(user_id, updated_at);
	`)
}

func tokenAuthMiddleware(db *sql.DB) gin.HandlerFunc {
	return func(c *gin.Context) {
		token := c.GetHeader("Authorization")
		if token == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "缺少 Authorization 请求头"})
			return
		}
		// 此处查询或校验 Token 对应 userID，demo 固定写死
		c.Set("user_id", "default_user")
		c.Next()
	}
}
```

---

## 六、 部署实施与运维方案

### 1. Docker 容器化一键部署
编写 `Dockerfile`：
```dockerfile
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o fanzhou-sync .

FROM alpine:latest
RUN apk --no-cache add ca-certificates tzdata
WORKDIR /data
COPY --from=builder /app/fanzhou-sync /usr/local/bin/fanzhou-sync
EXPOSE 8088
VOLUME ["/data"]
ENTRYPOINT ["/usr/local/bin/fanzhou-sync"]
```

### 2. 飞牛私有云 (fnOS) / 群晖 NAS Docker Compose 编排
```yaml
version: '3.8'
services:
  fanzhou-sync:
    image: fanzhou-sync:latest
    container_name: fanzhou-sync
    restart: unless-stopped
    ports:
      - "8088:8088"
    volumes:
      - /vol2/1000/docker/fanzhou-sync/data:/data
    environment:
      - TZ=Asia/Shanghai
```
数据目录持久化在 NAS 的存储池中，SQLite 数据库文件与配置文件快照每日可自动配合 NAS 原生的快照保护策略，安全性极高。

---

## 七、 落地推进路线图建议

| 阶段 | 周期建议 | 主要实施任务 | 交付物 |
| :--- | :--- | :--- | :--- |
| **阶段 1：服务端独立构建** | 1 ~ 2 天 | • 完成 Go API 核心工程结构初始化<br>• 实现 SQLite 数据表与增量同步路由<br>• 编写单元测试模拟多客户端并发更新 | 独立可运行的 Go 同步服务端程序 + Docker 镜像 |
| **阶段 2：Android 客户端协议对接** | 1 ~ 2 天 | • 封装 `GoSyncClient` 与网络交互层<br>• 在播放器退出/切集挂钩增量更新<br>• 在 App 启动时执行 `since` 差量合并 | 具备自动增量同步能力的客户端测试包 |
| **阶段 3：UI 交互与快照管理完善** | 1 天 | • 设置页「备份同步」新增专属服务配置面板<br>• 提供连接测试、历史配置快照列表拉取与一键回滚 | 完整的端到端用户界面体验 |
| **阶段 4：联调与双机多屏演练** | 1 天 | • 模拟手机与平板同时观看、交替离线切集<br>• 验证断网重试、无缝接播与时间戳仲裁逻辑 | 稳定成熟的生产级多端实时同步解决方案 |

---

## 八、 总结与建议

该方案通过 **Go 的高性能单二进制优势** 结合 **SQLite 的高并发 WAL 存储特性**，彻底解决了纯文件同步模式下的「并发覆盖竞态」与「流量臃肿」两大核心顽疾：
1. **轻量稳定**：NAS 上仅占 10MB 左右内存，不消耗额外服务器计算资源；
2. **体验质变**：播放进度秒级静默同步，手机看到一半，平板或电视打开即从精确秒数继续播放；
3. **安全可靠**：配置与进度多重隔离，配合 SQLite 事务与快照回滚机制，数据绝无丢失风险。
