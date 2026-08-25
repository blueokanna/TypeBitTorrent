# Settings reference

The settings dialog mirrors qBittorrent's category layout. This page states,
per option, whether it is **live** (actually drives the engine/bridge),
**stored** (persisted and shown, but not wired to the 0.1.1 engine), or
**restart** (takes effect at the next engine start).

## 行为 (Behavior)

| Option | Status |
| --- | --- |
| 语言 | stored (affects UI labels on next load; app strings are zh-CN) |
| 退出/删除/移除标签确认 | live (dialogs) |
| 启动最小化 / 最小化到托盘 / 关闭到托盘 | stored (tray is roadmap on desktop) |
| 通知开关 | stored |
| 刷新间隔 | **live** (poll cadence) |

## 外观 (Appearance)

| Option | Status |
| --- | --- |
| 主题模式（跟随系统 / 亮色 / 暗色 / AMOLED） | **live** (drives the MD3 scheme; AMOLED = pure-black dark surfaces) |
| 启用壁纸 + 选择/清除壁纸 | **live** (wallpaper fills the window behind a blurred + DIM'd scrim; Android copies the pick to app-private storage) |
| 模糊半径 / DIM 强度 | **live** (recompose the wallpaper layer; readability scrim guarantees text contrast) |
| 种子色 (hex) / 自动提取 | **live** (seed → HCT → full Monet palette via the in-repo CAM16 engine) |
| 壁纸预览 | **live** (renders the exact wallpaper layer + sample surfaces in a dialog) |

## 下载 (Downloads)

| Option | Status |
| --- | --- |
| 默认保存目录 | **live** (passed to every add) |
| 临时目录 | stored (engine writes directly to the save dir) |
| 预分配磁盘 | stored (the engine always `set_len`-preallocates on start) |
| 以暂停状态添加 | **live** |
| 最大活动下载/上传/种子数 | stored (no engine-side admission control) |
| 自动种子管理 (TMM) + 分类路径 | stored (categories are app-level; path mapping is not applied) |
| 分享率/时间限制、停止条件 | stored (engine has no seeding-limit hook) |

## 连接 (Connection)

| Option | Status |
| --- | --- |
| 监听端口 / 随机端口 | **live at engine start** (rebinding requires restart) |
| 最大连接数 / 每种子连接数 / 上传槽位 | partially live — `max_peers`/pipeline feed the engine; global conn/slot caps are host-side |
| 协议 TCP/UDP | stored (bridge always opens both) |
| 网络接口 | stored |
| 代理 | stored |
| IP 过滤器 | stored |
| 匿名模式 | stored |
| 向所有 Tracker/层级通告 | stored (engine has its own round-robin policy) |
| Peer TOS | stored |

## 速度 (Speed)

| Option | Status |
| --- | --- |
| 全局下载/上传限制 | **live** — token bucket in the native host, applied within one tick |
| 备用限制 + 时间表 | **live** — the store re-applies limits when the window opens/closes |
| 慢速种子检测 | stored |

## BitTorrent

| Option | Status |
| --- | --- |
| DHT | **live at engine start** |
| PEX | **live** (engine enables PEX by default; the toggle is stored) |
| LSD | stored |
| UPnP / NAT-PMP | **live at engine start** (typebit 0.1.1 port mapping) |
| 加密模式 | stored (plaintext wire in 0.1.1) |
| 每种子最大 Peers | **live for new torrents** |
| 请求流水线 / Endgame | **live for new torrents** |
| 智能分块调度 + 调度器权重 | **live for new torrents** |
| 使用默认 Tracker + 额外 Tracker | **live for new torrents** |
| 磁盘缓存 | **live at engine start** |
| 做种/下载槽位、乐观间隔、快照超时 | **live for new torrents** |
| 内容布局 | stored |

## WebUI

All options are **stored** — the WebUI server is a roadmap item.

## 高级 (Advanced)

`diskCacheBytes` and `saveResumeDataIntervalSec` are **live at engine
start** / for the poll loop respectively. The remaining options (uTP mode,
socket backlog, tracker retry policy, peer resolution, …) are **stored**
for qBittorrent familiarity; the 0.1.1 engine does not expose setters for
them.

## RSS

**Live** — feeds are fetched over HTTP and parsed with the JVM's built-in
XML parser. `smartEpisodeFilter` and the auto-download reporter are stored.

## How to read this

"live for new torrents" means the value is pushed to the engine's
`SessionConfig` and applies to torrents added afterwards. Tracker URLs and
per-file priorities ARE runtime-settable in typebit 0.1.1 (add/remove
without restart, selective download), so the detail tabs apply them live.
